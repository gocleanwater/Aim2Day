package com.aim2day.goal.goals.criteria;

import static org.assertj.core.api.Assertions.assertThat;

import com.aim2day.goal.goals.criteria.dto.CriteriaDtos.AchievementRequest;
import com.aim2day.goal.goals.criteria.dto.CriteriaDtos.ConfirmCriteriaRequest;
import com.aim2day.goal.goals.criteria.dto.CriteriaDtos.CriteriaResponse;
import com.aim2day.goal.goals.criteria.dto.CriteriaDtos.SaveCriteriaRequest;
import com.aim2day.goal.goals.yearly.YearlyGoalService;
import com.aim2day.goal.goals.yearly.dto.YearlyGoalDtos.CreateYearlyGoalRequest;
import com.aim2day.goal.support.GoalTestSupport;
import com.aim2day.goal.support.TestClockConfig;
import com.aim2day.goal.support.TestSessionConfig;
import java.math.BigDecimal;
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

/**
 * 서로 다른 인스턴스가 같은 목표의 기준을 동시에 바꾸는 상황. 연간 목표 행 잠금이 없으면 두 요청이 같은
 * "이전 달성률"을 읽어 진행 기록의 이전·이후 값이 이어지지 않는다.
 */
@SpringBootTest
@Import({TestSessionConfig.class, TestClockConfig.class})
class CriteriaConcurrencyTests {

	@Autowired
	YearlyGoalService goalService;
	@Autowired
	CriteriaService criteriaService;
	@Autowired
	JdbcTemplate jdbc;

	@BeforeEach
	void reset() {
		GoalTestSupport.clean(jdbc);
	}

	@Test
	void concurrentAchievementsKeepRateAndLogChainConsistent() throws Exception {
		long goal = goalService.create(1L, new CreateYearlyGoalRequest(null, "목표", null, null)).id();
		CriteriaResponse draft = criteriaService.save(1L, goal, new SaveCriteriaRequest(0L, List.of(
				new SaveCriteriaRequest.Item(null, "A", 40), new SaveCriteriaRequest.Item(null, "B", 10),
				new SaveCriteriaRequest.Item(null, "C", 50)), null));
		long a = draft.items().get(0).id();
		long b = draft.items().get(1).id();
		long c = draft.items().get(2).id();
		criteriaService.confirm(1L, goal, new ConfirmCriteriaRequest(1L, List.of(b)));

		ExecutorService pool = Executors.newFixedThreadPool(2);
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch go = new CountDownLatch(1);
		List<Future<Object>> results = new ArrayList<>();
		try {
			for (long criterion : new long[] {a, c}) {
				results.add(pool.submit(() -> {
					ready.countDown();
					go.await();
					return criteriaService.achieve(1L, goal, criterion, new AchievementRequest(true, null));
				}));
			}
			ready.await();
			go.countDown();
			for (Future<Object> f : results) {
				f.get(30, TimeUnit.SECONDS);
			}
		} finally {
			pool.shutdownNow();
		}

		List<Map<String, Object>> logs = jdbc.queryForList(
				"SELECT * FROM goal_progress_logs WHERE target_id = ? ORDER BY id", goal);
		// 확정 1건 + 달성 2건. 두 달성 기록은 이전·이후 값이 끊기지 않고 이어져야 한다(10 -> x -> 100).
		assertThat(logs).hasSize(3);
		BigDecimal firstNew = (BigDecimal) logs.get(1).get("NEW_RATE");
		assertThat((BigDecimal) logs.get(1).get("PREVIOUS_RATE")).isEqualByComparingTo("10.00");
		assertThat((BigDecimal) logs.get(2).get("PREVIOUS_RATE")).isEqualByComparingTo(firstNew);
		assertThat((BigDecimal) logs.get(2).get("NEW_RATE")).isEqualByComparingTo("100.00");
	}
}
