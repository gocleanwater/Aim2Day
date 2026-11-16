package com.aim2day.goal.goals.criteria.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record AchievementResponse(long criterionId, @JsonProperty("isAchieved") boolean isAchieved,
		int achievementRate) {
}
