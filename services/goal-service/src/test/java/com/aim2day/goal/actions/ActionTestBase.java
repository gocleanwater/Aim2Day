package com.aim2day.goal.actions;

import com.aim2day.goal.support.GoalApiClient;
import com.aim2day.goal.support.GoalTestSupport;
import com.aim2day.goal.support.MutableClock;
import com.aim2day.goal.support.TestClockConfig;
import com.aim2day.goal.support.TestSessionConfig;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.session.MapSessionRepository;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 행동 API 테스트 공통 준비. 시계는 2026-11-12T03:00Z(서울 목요일 12:00)이고 주 시작은 일요일이라 이번 주는 11-08~11-14다.
 * 사용자 1에게 연간 -> 월간(2026-11) -> 체크형 주간(w1)·수치형 주간(n1, 목표 10)을 API로 만들어 둔다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestSessionConfig.class, TestClockConfig.class})
abstract class ActionTestBase {

	@Autowired
	MockMvc mockMvc;
	@Autowired
	MapSessionRepository sessionRepository;
	@Autowired
	JdbcTemplate jdbc;
	@Autowired
	MutableClock clock;

	GoalApiClient api;
	long yearly;
	long monthly;
	long w1;
	long n1;

	@BeforeEach
	void seedGoals() throws Exception {
		clock.reset();
		GoalTestSupport.clean(jdbc);
		api = new GoalApiClient(mockMvc, sessionRepository);
		yearly = api.yearly(1L, "연간");
		monthly = api.monthly(1L, yearly, "월간");
		w1 = api.weekly(1L, monthly, "체크 주간");
		n1 = api.numericWeekly(1L, monthly, "수치 주간", "10");
	}

	BigDecimal currentValue(long weekly) {
		return jdbc.queryForObject("SELECT current_value FROM weekly_goals WHERE id = ?", BigDecimal.class, weekly);
	}

	BigDecimal weeklyRate(long weekly) {
		return jdbc.queryForObject("SELECT progress_rate FROM weekly_goals WHERE id = ?", BigDecimal.class, weekly);
	}

	BigDecimal monthlyRate(long monthlyId) {
		return jdbc.queryForObject("SELECT progress_rate FROM monthly_goals WHERE id = ?", BigDecimal.class,
				monthlyId);
	}

	int count(String sql, Object... args) {
		return jdbc.queryForObject(sql, Integer.class, args);
	}

	int records(long weekly) {
		return count("SELECT COUNT(*) FROM weekly_goal_numeric_records WHERE weekly_goal_id = ?", weekly);
	}
}
