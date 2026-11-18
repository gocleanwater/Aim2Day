package com.aim2day.goal.goals.weekly.record;

import static org.assertj.core.api.Assertions.assertThat;

import com.aim2day.goal.goals.weekly.WeeklyGoalService;
import com.aim2day.goal.goals.weekly.dto.WeeklyGoalDtos.CreateWeeklyGoalRequest;
import com.aim2day.goal.goals.weekly.record.dto.NumericRecordDtos.CreateNumericRecordRequest;
import com.aim2day.goal.support.GoalTestSupport;
import com.aim2day.goal.support.MutableClock;
import com.aim2day.goal.support.TestClockConfig;
import com.aim2day.goal.support.TestSessionConfig;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
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
class NumericRecordConcurrencyTests {

	@Autowired
	NumericRecordService records;
	@Autowired
	WeeklyGoalService weeklies;
	@Autowired
	JdbcTemplate jdbc;
	@Autowired
	MutableClock clock;

	long monthly;
	long w1;
	long w2;

	@BeforeEach
	void seed() {
		clock.reset();
		GoalTestSupport.clean(jdbc);
		long yearly = GoalTestSupport.seedYearly(jdbc, 1L, 2026, "연간");
		monthly = GoalTestSupport.seedMonthly(jdbc, yearly, LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30),
				"월간");
		w1 = GoalTestSupport.seedWeekly(jdbc, monthly, LocalDate.of(2026, 11, 8), LocalDate.of(2026, 11, 14), "w1",
				"NUMERIC", new BigDecimal("100.00"));
		w2 = GoalTestSupport.seedWeekly(jdbc, monthly, LocalDate.of(2026, 11, 8), LocalDate.of(2026, 11, 14), "w2",
				"NUMERIC", new BigDecimal("100.00"));
	}

	private BigDecimal decimal(String sql, long id) {
		return jdbc.queryForObject(sql, BigDecimal.class, id);
	}

	/**
	 * 같은 월간 아래 두 주간 목표에 동시에 기록하고, 같은 월간 아래 새 주간 목표도 동시에 만든다.
	 * 기여량이 하나도 사라지지 않고, 월간 진행률은 최종 주간 값들의 가중 평균과 같아야 한다.
	 */
	@Test
	void concurrentRecordsAndCreatesKeepMonthlyConsistent() throws Exception {
		int threads = 12;
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		CountDownLatch ready = new CountDownLatch(threads);
		CountDownLatch go = new CountDownLatch(1);
		List<Future<?>> futures = new ArrayList<>();
		try {
			for (int i = 0; i < threads; i++) {
				int n = i;
				Callable<Object> task = () -> {
					ready.countDown();
					go.await();
					if (n >= 10) {
						// 같은 월간 아래 새 주간 목표 생성. 0%로 분모에 들어가 월간 평균을 바꾼다.
						return weeklies.create(1L, new CreateWeeklyGoalRequest(monthly, "동시 생성 " + n, null, null,
								null, null, null));
					}
					return records.add(1L, n % 2 == 0 ? w1 : w2,
							new CreateNumericRecordRequest(BigDecimal.ONE, "동시 " + n));
				};
				futures.add(pool.submit(task));
			}
			ready.await();
			go.countDown();
			for (Future<?> f : futures) {
				f.get(60, TimeUnit.SECONDS);
			}
		} finally {
			pool.shutdownNow();
		}
		assertThat(decimal("SELECT current_value FROM weekly_goals WHERE id = ?", w1)).isEqualByComparingTo("5.00");
		assertThat(decimal("SELECT current_value FROM weekly_goals WHERE id = ?", w2)).isEqualByComparingTo("5.00");
		assertThat(decimal("SELECT progress_rate FROM weekly_goals WHERE id = ?", w1)).isEqualByComparingTo("5.00");
		// 하위 주간 4개(5%, 5%, 0%, 0%)의 평균. 기록과 생성 중 어느 쪽이 나중에 월간을 계산해도 결과가 같아야 한다.
		assertThat(decimal("SELECT progress_rate FROM monthly_goals WHERE id = ?", monthly))
				.isEqualByComparingTo("2.50");
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM weekly_goals WHERE monthly_goal_id = ?", Integer.class,
				monthly)).isEqualTo(4);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM weekly_goal_numeric_records", Integer.class))
				.isEqualTo(10);
	}

	/** 같은 주간 목표에 20건을 동시에 기록해도 current_value가 정확히 합계다. */
	@Test
	void concurrentRecordsOnSameWeeklySum() throws Exception {
		int threads = 20;
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		CountDownLatch ready = new CountDownLatch(threads);
		CountDownLatch go = new CountDownLatch(1);
		List<Future<?>> futures = new ArrayList<>();
		try {
			for (int i = 0; i < threads; i++) {
				futures.add(pool.submit(() -> {
					ready.countDown();
					go.await();
					return records.add(1L, w1, new CreateNumericRecordRequest(BigDecimal.ONE, null));
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
		assertThat(decimal("SELECT current_value FROM weekly_goals WHERE id = ?", w1)).isEqualByComparingTo("20.00");
		assertThat(decimal("SELECT progress_rate FROM weekly_goals WHERE id = ?", w1)).isEqualByComparingTo("20.00");
		// 월간은 (20 + 0) / 2 = 10.
		assertThat(decimal("SELECT progress_rate FROM monthly_goals WHERE id = ?", monthly))
				.isEqualByComparingTo("10.00");
	}
}
