package com.aim2day.goal.actions;

import static org.assertj.core.api.Assertions.assertThat;

import com.aim2day.goal.actions.dto.ActionDtos.CompletionRequest;
import com.aim2day.goal.support.GoalTestSupport;
import com.aim2day.goal.support.MutableClock;
import com.aim2day.goal.support.TestClockConfig;
import com.aim2day.goal.support.TestSessionConfig;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 서비스 계층 동시성(인스턴스 3개가 같은 요청을 나눠 받는 상황의 H2 근사). 실제 MySQL 행 잠금·교착 동작은
 * 서버 E2E에서 확인한다.
 */
@SpringBootTest
@Import({TestSessionConfig.class, TestClockConfig.class})
class ActionCompletionConcurrencyTests {

	@Autowired
	ActionService actions;
	@Autowired
	JdbcTemplate jdbc;
	@Autowired
	MutableClock clock;

	long monthly;
	long numeric;
	long checklist;

	@BeforeEach
	void seed() {
		clock.reset();
		GoalTestSupport.clean(jdbc);
		long yearly = GoalTestSupport.seedYearly(jdbc, 1L, 2026, "연간");
		monthly = GoalTestSupport.seedMonthly(jdbc, yearly, LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30),
				"월간");
		numeric = GoalTestSupport.seedWeekly(jdbc, monthly, LocalDate.of(2026, 11, 8), LocalDate.of(2026, 11, 14),
				"수치", "NUMERIC", new BigDecimal("100.00"));
		checklist = GoalTestSupport.seedWeekly(jdbc, monthly, LocalDate.of(2026, 11, 8), LocalDate.of(2026, 11, 14),
				"체크", "CHECKLIST", null);
	}

	private long numericAction(String amount) {
		long id = GoalTestSupport.seedAction(jdbc, 1L, numeric, "수치 행동", LocalDate.of(2026, 11, 12), false, false);
		jdbc.update("UPDATE actions SET contribution_value = ? WHERE id = ?", new BigDecimal(amount), id);
		return id;
	}

	private void runAll(List<Runnable> tasks) throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
		CountDownLatch ready = new CountDownLatch(tasks.size());
		CountDownLatch go = new CountDownLatch(1);
		List<Future<?>> futures = new ArrayList<>();
		try {
			for (Runnable task : tasks) {
				futures.add(pool.submit(() -> {
					ready.countDown();
					go.await();
					task.run();
					return null;
				}));
			}
			ready.await();
			go.countDown();
			for (Future<?> f : futures) {
				f.get(60, TimeUnit.SECONDS);
			}
		} finally {
			pool.shutdownNow();
		}
	}

	/** 같은 행동을 여러 인스턴스가 동시에 완료해도 수치 기록은 1행이고 current_value는 한 번만 더해진다. */
	@Test
	void concurrentSameActionCompletion() throws Exception {
		long action = numericAction("2");
		List<Runnable> tasks = new ArrayList<>();
		for (int i = 0; i < 8; i++) {
			tasks.add(() -> actions.complete(1L, action, new CompletionRequest(true, null)));
		}
		runAll(tasks);

		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM weekly_goal_numeric_records WHERE action_id = ?",
				Integer.class, action)).isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT current_value FROM weekly_goals WHERE id = ?", BigDecimal.class,
				numeric)).isEqualByComparingTo("2.00");
		assertThat(jdbc.queryForObject("SELECT progress_rate FROM weekly_goals WHERE id = ?", BigDecimal.class,
				numeric)).isEqualByComparingTo("2.00");
		// 진행 기록도 한 번만(주간 0 -> 2, 월간 0 -> 1).
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM goal_progress_logs WHERE target_type = 'WEEKLY_GOAL'",
				Integer.class)).isEqualTo(1);
	}

	/** 서로 다른 행동(기여 2, 1, 4)을 같은 주간 목표에 동시에 완료해도 기여량이 하나도 사라지지 않는다(ERD 결정 16). */
	@Test
	void concurrentDifferentActionsOnSameWeekly() throws Exception {
		long a = numericAction("2");
		long b = numericAction("1");
		long c = numericAction("4");
		List<Runnable> tasks = new ArrayList<>();
		for (long id : new long[] {a, b, c}) {
			tasks.add(() -> actions.complete(1L, id, new CompletionRequest(true, null)));
		}
		runAll(tasks);

		assertThat(jdbc.queryForObject("SELECT current_value FROM weekly_goals WHERE id = ?", BigDecimal.class,
				numeric)).isEqualByComparingTo("7.00");
		assertThat(jdbc.queryForObject("SELECT progress_rate FROM weekly_goals WHERE id = ?", BigDecimal.class,
				numeric)).isEqualByComparingTo("7.00");
		// 월간 = (7 * 1 + 0 * 1) / 2
		assertThat(jdbc.queryForObject("SELECT progress_rate FROM monthly_goals WHERE id = ?", BigDecimal.class,
				monthly)).isEqualByComparingTo("3.50");
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM weekly_goal_numeric_records", Integer.class)).isEqualTo(3);
	}

	/** 같은 월간 아래 두 주간 목표의 행동을 동시에 완료하면 월간 진행률이 두 변경을 모두 반영한다. */
	@Test
	void concurrentCompletionsUnderTheSameMonthly() throws Exception {
		long a = numericAction("10");
		long c1 = GoalTestSupport.seedAction(jdbc, 1L, checklist, "체크 1", LocalDate.of(2026, 11, 12), false, false);
		List<Runnable> tasks = List.of(
				() -> actions.complete(1L, a, new CompletionRequest(true, null)),
				() -> actions.complete(1L, c1, new CompletionRequest(true, null)));
		runAll(tasks);

		assertThat(jdbc.queryForObject("SELECT progress_rate FROM weekly_goals WHERE id = ?", BigDecimal.class,
				numeric)).isEqualByComparingTo("10.00");
		assertThat(jdbc.queryForObject("SELECT progress_rate FROM weekly_goals WHERE id = ?", BigDecimal.class,
				checklist)).isEqualByComparingTo("100.00");
		assertThat(jdbc.queryForObject("SELECT progress_rate FROM monthly_goals WHERE id = ?", BigDecimal.class,
				monthly)).isEqualByComparingTo("55.00");
	}

	/** 완료와 취소를 번갈아 여러 번 보내도 마지막에 current_value가 일관된다(완료 상태와 기록이 어긋나지 않는다). */
	@Test
	void completeAndCancelRaceKeepsRecordConsistentWithState() throws Exception {
		long action = numericAction("3");
		List<Runnable> tasks = new ArrayList<>();
		for (int i = 0; i < 10; i++) {
			boolean completed = i % 2 == 0;
			tasks.add(() -> actions.complete(1L, action, new CompletionRequest(completed, null)));
		}
		runAll(tasks);

		boolean isCompleted = Boolean.TRUE.equals(jdbc.queryForObject(
				"SELECT is_completed FROM actions WHERE id = ?", Boolean.class, action));
		int records = jdbc.queryForObject("SELECT COUNT(*) FROM weekly_goal_numeric_records WHERE action_id = ?",
				Integer.class, action);
		BigDecimal current = jdbc.queryForObject("SELECT current_value FROM weekly_goals WHERE id = ?",
				BigDecimal.class, numeric);
		assertThat(records).isEqualTo(isCompleted ? 1 : 0);
		assertThat(current).isEqualByComparingTo(isCompleted ? "3.00" : "0.00");
	}
}
