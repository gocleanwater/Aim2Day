package com.aim2day.goal.goals.yearly.dto;

import java.util.List;

public record YearlyGoalListResponse(int year, int activeCount, int maxCount, List<YearlyGoalResponse> items) {
}
