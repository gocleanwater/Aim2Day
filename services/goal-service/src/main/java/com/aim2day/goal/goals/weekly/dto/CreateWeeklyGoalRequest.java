package com.aim2day.goal.goals.weekly.dto;

import com.aim2day.goal.goals.weekly.MeasurementType;
import java.math.BigDecimal;

/**
 * 기간은 받지 않는다(생성 시점의 이번 주로 서버가 정한다). measurementType 생략은 CHECKLIST, weight 생략은 1이다.
 * 검증은 서비스가 한다(NUMERIC일 때만 targetValue 필수 같은 조건부 규칙이라서).
 */
public record CreateWeeklyGoalRequest(Long monthlyGoalId, String title, String description, String color,
		MeasurementType measurementType, BigDecimal targetValue, BigDecimal weight) {
}
