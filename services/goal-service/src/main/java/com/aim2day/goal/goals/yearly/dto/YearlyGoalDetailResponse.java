package com.aim2day.goal.goals.yearly.dto;

import java.util.List;

/** 월간·주간 하위 트리는 단위 3에서 채운다. 그때까지 monthlyGoals는 빈 배열이다. */
public record YearlyGoalDetailResponse(YearlyGoalResponse goal, List<Object> monthlyGoals) {
}
