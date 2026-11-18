package com.aim2day.goal.onboarding.dto;

import com.aim2day.goal.goals.weekly.MeasurementType;
import java.math.BigDecimal;
import java.util.List;

/**
 * 온보딩 일괄 저장 본문(API 명세 §4.2 complete). 새 항목은 tempId로 서로 참조하고, 기존 목표는 실제 id로 참조한다.
 * 부모 지정은 ...TempId와 ...Id 중 하나만 쓴다. 필드 검증은 개별 생성 API의 규칙을 서비스가 재사용해 한다.
 * 연간 목표는 현재 연도, 월간은 현재 달, 주간은 현재 주로 서버가 정하므로 기간·연도는 받지 않는다.
 */
public record CompleteRequest(List<YearlyItem> yearlyGoals, List<MonthlyItem> monthlyGoals,
		List<WeeklyItem> weeklyGoals, List<ActionItem> actions) {

	public record YearlyItem(String tempId, String title, String description, String color) {
	}

	public record MonthlyItem(String tempId, String yearlyGoalTempId, Long yearlyGoalId, String title,
			String description, String color) {
	}

	public record WeeklyItem(String tempId, String monthlyGoalTempId, Long monthlyGoalId, String title,
			String description, String color, MeasurementType measurementType, BigDecimal targetValue,
			BigDecimal weight) {
	}
}
