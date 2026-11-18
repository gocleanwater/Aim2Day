package com.aim2day.goal.onboarding.dto;

public record StatusResponse(boolean needsOnboarding, boolean hasDraft, int currentYear, int activeYearlyGoalCount,
		int maxYearlyGoalCount, String firstQuestion) {
}
