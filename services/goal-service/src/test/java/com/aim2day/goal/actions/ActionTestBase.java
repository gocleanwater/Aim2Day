package com.aim2day.goal.actions;

import com.aim2day.goal.support.ApiTestBase;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;

/**
 * 행동 API 테스트 공통 준비. 시계는 2026-11-12T03:00Z(서울 목요일 12:00)이고 주 시작은 일요일이라 이번 주는 11-08~11-14다.
 * 사용자 1에게 연간 -> 월간(2026-11) -> 체크형 주간(w1)·수치형 주간(n1, 목표 10)을 API로 만들어 둔다.
 */
abstract class ActionTestBase extends ApiTestBase {

	long yearly;
	long monthly;
	long w1;
	long n1;

	@BeforeEach
	void seedGoals() throws Exception {
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

	int records(long weekly) {
		return count("SELECT COUNT(*) FROM weekly_goal_numeric_records WHERE weekly_goal_id = ?", weekly);
	}
}
