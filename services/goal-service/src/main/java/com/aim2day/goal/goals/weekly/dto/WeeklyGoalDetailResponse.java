package com.aim2day.goal.goals.weekly.dto;

import com.aim2day.goal.goals.GoalStatus;
import com.aim2day.goal.goals.ProgressView;
import com.aim2day.goal.goals.weekly.MeasurementType;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** 주간 목표 객체에 연결 행동 actions를 더한 응답(§4.6). 행동은 단위 4에서 채우므로 지금은 빈 배열이다. */
public record WeeklyGoalDetailResponse(long id, WeeklyGoalResponse.Path path, LocalDate startDate, LocalDate endDate,
		String title, String description, String color, String displayColor, MeasurementType measurementType,
		BigDecimal targetValue, BigDecimal currentValue, @JsonProperty("isTargetReached") Boolean isTargetReached,
		BigDecimal weight, GoalStatus status, GoalStatus pathStatus, @JsonProperty("isDelayed") boolean isDelayed,
		ProgressView progress, WeeklyGoalResponse.ActionCountsView actionCounts, long version,
		List<Object> actions) {

	public static WeeklyGoalDetailResponse of(WeeklyGoalResponse w, List<Object> actions) {
		return new WeeklyGoalDetailResponse(w.id(), w.path(), w.startDate(), w.endDate(), w.title(),
				w.description(), w.color(), w.displayColor(), w.measurementType(), w.targetValue(),
				w.currentValue(), w.isTargetReached(), w.weight(), w.status(), w.pathStatus(), w.isDelayed(),
				w.progress(), w.actionCounts(), w.version(), actions);
	}
}
