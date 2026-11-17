package com.aim2day.goal.goals.yearly.dto;

import com.aim2day.goal.goals.monthly.dto.MonthlyGoalDetailResponse;
import java.util.List;

/** 연간 목표 객체에 삭제되지 않은 월간 목표와 그 하위 주간 목표 트리를 더한 응답(API 명세 §4.3). */
public record YearlyGoalDetailResponse(YearlyGoalResponse goal, List<MonthlyGoalDetailResponse> monthlyGoals) {
}
