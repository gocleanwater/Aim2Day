package com.aim2day.goal.goals.yearly;

import static org.assertj.core.api.Assertions.assertThat;

import com.aim2day.goal.common.exception.BusinessException;
import com.aim2day.goal.goals.GoalErrorCode;
import com.aim2day.goal.goals.yearly.dto.YearlyGoalDtos.CreateYearlyGoalRequest;
import com.aim2day.goal.support.GoalTestSupport;
import com.aim2day.goal.support.TestClockConfig;
import com.aim2day.goal.support.TestSessionConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
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

@SpringBootTest
@Import({TestSessionConfig.class, TestClockConfig.class})
class YearlyGoalLimitConcurrencyTests {

	@Autowired
	YearlyGoalService service;
	@Autowired
	JdbcTemplate jdbc;

	@BeforeEach
	void reset() {
		GoalTestSupport.clean(jdbc);
	}

	/**
	 * 활성 목표가 2개일 때 서로 다른 인스턴스가 동시에 생성하는 상황. 사용자 행 잠금이 없으면 모두 "2개"를 보고
	 * 통과해 한도(3개)를 넘는다. 정확히 1건만 성공하고 나머지는 GOAL_LIMIT_EXCEEDED여야 한다.
	 */
	@Test
	void concurrentCreatesRespectLimit() throws Exception {
		service.create(1L, new CreateYearlyGoalRequest(null, "a", null, null));
		service.create(1L, new CreateYearlyGoalRequest(null, "b", null, null));

		int threads = 4;
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		CountDownLatch ready = new CountDownLatch(threads);
		CountDownLatch go = new CountDownLatch(1);
		List<Future<Object>> results = new ArrayList<>();
		try {
			for (int i = 0; i < threads; i++) {
				String title = "동시 " + i;
				results.add(pool.submit(() -> {
					ready.countDown();
					go.await();
					return service.create(1L, new CreateYearlyGoalRequest(null, title, null, null));
				}));
			}
			ready.await();
			go.countDown();
			int success = 0;
			int limited = 0;
			for (Future<Object> f : results) {
				try {
					f.get(30, TimeUnit.SECONDS);
					success++;
				} catch (ExecutionException e) {
					assertThat(e.getCause()).isInstanceOf(BusinessException.class);
					assertThat(((BusinessException) e.getCause()).getErrorCode())
							.isEqualTo(GoalErrorCode.GOAL_LIMIT_EXCEEDED);
					limited++;
				}
			}
			assertThat(success).isEqualTo(1);
			assertThat(limited).isEqualTo(threads - 1);
		} finally {
			pool.shutdownNow();
		}
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM yearly_goals WHERE user_id = 1", Integer.class))
				.isEqualTo(3);
	}
}
