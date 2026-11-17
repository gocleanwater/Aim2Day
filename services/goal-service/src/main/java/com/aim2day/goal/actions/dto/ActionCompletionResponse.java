package com.aim2day.goal.actions.dto;

import com.aim2day.goal.goals.ProgressView;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;

/** 완료 응답. MAINTENANCE이면 weeklyGoal, monthlyGoal이 null이다. 체크형이면 currentValue, isTargetReached가 null이다. */
public record ActionCompletionResponse(ActionResponse action, WeeklyView weeklyGoal, MonthlyView monthlyGoal) {

	public record WeeklyView(long id, ProgressView progress, BigDecimal currentValue,
			@JsonProperty("isTargetReached") Boolean isTargetReached) {
	}

	public record MonthlyView(long id, ProgressView progress) {
	}
}
