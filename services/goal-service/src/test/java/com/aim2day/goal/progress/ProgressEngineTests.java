package com.aim2day.goal.progress;

import static org.assertj.core.api.Assertions.assertThat;

import com.aim2day.goal.common.lock.UserRowLock;
import com.aim2day.goal.goals.weekly.WeeklyGoal;
import com.aim2day.goal.goals.weekly.WeeklyGoalRepository;
import com.aim2day.goal.support.GoalTestSupport;
import com.aim2day.goal.support.TestClockConfig;
import com.aim2day.goal.support.TestSessionConfig;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@Import({TestSessionConfig.class, TestClockConfig.class})
class ProgressEngineTests {

	private static final LocalDate DAY = LocalDate.of(2026, 11, 12);

	@Autowired
	ProgressEngine engine;
	@Autowired
	UserRowLock userRowLock;
	@Autowired
	WeeklyGoalRepository weeklies;
	@Autowired
	JdbcTemplate jdbc;
	@Autowired
	PlatformTransactionManager txManager;

	long monthlyId;
	long weeklyId;

	@BeforeEach
	void seed() {
		GoalTestSupport.clean(jdbc);
		long yearlyId = GoalTestSupport.seedYearly(jdbc, 1L, 2026, "연간");
		monthlyId = GoalTestSupport.seedMonthly(jdbc, yearlyId, LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30),
				"월간");
		weeklyId = GoalTestSupport.seedWeekly(jdbc, monthlyId, LocalDate.of(2026, 11, 8), LocalDate.of(2026, 11, 14),
				"주간", "NUMERIC", new BigDecimal("10.00"));
	}

	private <T> T inTx(java.util.function.Supplier<T> body) {
		return new TransactionTemplate(txManager).execute(status -> body.get());
	}

	private BigDecimal weeklyRate() {
		return jdbc.queryForObject("SELECT progress_rate FROM weekly_goals WHERE id = ?", BigDecimal.class, weeklyId);
	}

	private BigDecimal weeklyValue() {
		return jdbc.queryForObject("SELECT current_value FROM weekly_goals WHERE id = ?", BigDecimal.class, weeklyId);
	}

	private BigDecimal monthlyRate() {
		return jdbc.queryForObject("SELECT progress_rate FROM monthly_goals WHERE id = ?", BigDecimal.class,
				monthlyId);
	}

	private int logCount() {
		return jdbc.queryForObject("SELECT COUNT(*) FROM goal_progress_logs", Integer.class);
	}

	@Test
	void numericDeltaIsAtomicAndRecalculates() {
		WeeklyProgress result = inTx(() -> {
			userRowLock.lock(1L);
			engine.addNumeric(weeklyId, new BigDecimal("3"));
			return engine.recalcWeekly(1L, weeklyId, DAY, "수치 기록: +3");
		});

		assertThat(result.rate()).isEqualByComparingTo("30.00");
		assertThat(result.currentValue()).isEqualByComparingTo("3.00");
		assertThat(result.targetReached()).isFalse();
		assertThat(weeklyRate()).isEqualByComparingTo("30.00");
		assertThat(weeklyValue()).isEqualByComparingTo("3.00");
		// 하위 주간 1개, 가중치 1이므로 월간도 30.00이다.
		assertThat(monthlyRate()).isEqualByComparingTo("30.00");

		List<Map<String, Object>> logs = jdbc.queryForList(
				"SELECT target_type, previous_rate, new_rate, change_type, log_date, memo "
						+ "FROM goal_progress_logs ORDER BY id");
		assertThat(logs).hasSize(2);
		assertThat(logs.get(0)).containsEntry("TARGET_TYPE", "WEEKLY_GOAL").containsEntry("CHANGE_TYPE",
				"PROGRESS_UPDATE");
		assertThat((BigDecimal) logs.get(0).get("PREVIOUS_RATE")).isEqualByComparingTo("0.00");
		assertThat((BigDecimal) logs.get(0).get("NEW_RATE")).isEqualByComparingTo("30.00");
		assertThat(logs.get(0)).containsEntry("MEMO", "수치 기록: +3");
		assertThat(logs.get(1)).containsEntry("TARGET_TYPE", "MONTHLY_GOAL");
		assertThat((BigDecimal) logs.get(1).get("NEW_RATE")).isEqualByComparingTo("30.00");
	}

	@Test
	void noLogWhenRateUnchanged() {
		// 값이 그대로면 기록을 남기지 않는다.
		inTx(() -> engine.recalcWeekly(1L, weeklyId, DAY, "변화 없음"));
		assertThat(logCount()).isZero();

		inTx(() -> {
			engine.addNumeric(weeklyId, new BigDecimal("3"));
			return engine.recalcWeekly(1L, weeklyId, DAY, "+3");
		});
		assertThat(logCount()).isEqualTo(2);

		inTx(() -> engine.recalcWeekly(1L, weeklyId, DAY, "다시 계산"));
		assertThat(logCount()).isEqualTo(2);
	}

	@Test
	void targetReachedUsesRawValueNotRoundedRate() {
		jdbc.update("UPDATE weekly_goals SET target_value = 10000.00 WHERE id = ?", weeklyId);
		WeeklyProgress result = inTx(() -> {
			engine.addNumeric(weeklyId, new BigDecimal("9999.99"));
			return engine.recalcWeekly(1L, weeklyId, DAY, "+9999.99");
		});
		assertThat(result.rate()).isEqualByComparingTo("100.00");
		assertThat(result.targetReached()).isFalse();

		WeeklyProgress reached = inTx(() -> {
			engine.addNumeric(weeklyId, new BigDecimal("0.01"));
			return engine.recalcWeekly(1L, weeklyId, DAY, "+0.01");
		});
		assertThat(reached.targetReached()).isTrue();
	}

	@Test
	void checklistWithoutActionsStaysZero() {
		long checklist = GoalTestSupport.seedWeekly(jdbc, monthlyId, LocalDate.of(2026, 11, 8),
				LocalDate.of(2026, 11, 14), "체크", "CHECKLIST", null);
		WeeklyProgress result = inTx(() -> engine.recalcWeekly(1L, checklist, DAY, "x"));
		assertThat(result.rate()).isEqualByComparingTo("0.00");
		assertThat(result.targetReached()).isNull();
		assertThat(logCount()).isZero();
	}

	@Test
	void monthlyIsWeightedAverageExcludingDeletedWeeklies() {
		long second = GoalTestSupport.seedWeekly(jdbc, monthlyId, LocalDate.of(2026, 11, 8), LocalDate.of(2026, 11, 14),
				"둘째", "NUMERIC", new BigDecimal("4.00"));
		long deleted = GoalTestSupport.seedWeekly(jdbc, monthlyId, LocalDate.of(2026, 11, 8),
				LocalDate.of(2026, 11, 14), "삭제됨", "CHECKLIST", null);
		jdbc.update("UPDATE weekly_goals SET weight = 2.00 WHERE id = ?", second);
		jdbc.update("UPDATE weekly_goals SET deleted_at = CURRENT_TIMESTAMP, progress_rate = 100.00 WHERE id = ?",
				deleted);

		inTx(() -> {
			engine.addNumeric(weeklyId, new BigDecimal("10"));
			engine.recalcWeekly(1L, weeklyId, DAY, "첫째 완료");
			engine.addNumeric(second, new BigDecimal("2"));
			return engine.recalcWeekly(1L, second, DAY, "둘째 절반");
		});

		// (100 x 1 + 50 x 2) / 3 = 66.67. 삭제된 주간(100%)은 분모와 분자에서 빠진다.
		assertThat(monthlyRate()).isEqualByComparingTo("66.67");
	}

	@Test
	void entitySaveDoesNotOverwriteProgress() {
		// 제목을 고치는 화면이 주간 목표를 읽은 뒤, 다른 요청이 +5를 더한 상황.
		WeeklyGoal stale = weeklies.findById(weeklyId).orElseThrow();
		inTx(() -> {
			engine.addNumeric(weeklyId, new BigDecimal("5"));
			return engine.recalcWeekly(1L, weeklyId, DAY, "+5");
		});

		stale.change("바뀐 제목", stale.getDescription(), stale.getColor(), stale.getStatus(), stale.getTargetValue(),
				stale.getWeight(), stale.getMonthlyGoalId());
		weeklies.saveAndFlush(stale);

		// 낡은 엔티티로 저장해도 current_value·progress_rate는 읽기 전용이라 DB의 값이 유지되고, version은 1이다.
		assertThat(weeklyValue()).isEqualByComparingTo("5.00");
		assertThat(weeklyRate()).isEqualByComparingTo("50.00");
		assertThat(jdbc.queryForObject("SELECT version FROM weekly_goals WHERE id = ?", Long.class, weeklyId))
				.isEqualTo(1L);
		assertThat(jdbc.queryForObject("SELECT title FROM weekly_goals WHERE id = ?", String.class, weeklyId))
				.isEqualTo("바뀐 제목");
	}

	/** 8개 스레드가 같은 주간 목표에 동시에 +1을 기록해도 기여량이 하나도 사라지지 않는다. */
	@Test
	void concurrentNumericRecordsSum() throws Exception {
		int threads = 8;
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		CountDownLatch ready = new CountDownLatch(threads);
		CountDownLatch go = new CountDownLatch(1);
		List<Future<?>> futures = new ArrayList<>();
		try {
			for (int i = 0; i < threads; i++) {
				futures.add(pool.submit(() -> {
					ready.countDown();
					go.await();
					return inTx(() -> {
						userRowLock.lock(1L);
						engine.addNumeric(weeklyId, BigDecimal.ONE);
						return engine.recalcWeekly(1L, weeklyId, DAY, "+1");
					});
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
		assertThat(weeklyValue()).isEqualByComparingTo("8.00");
		assertThat(weeklyRate()).isEqualByComparingTo("80.00");
		// 월간은 마지막으로 계산한 주간 값(최종값)과 같아야 한다.
		assertThat(monthlyRate()).isEqualByComparingTo("80.00");
	}
}
