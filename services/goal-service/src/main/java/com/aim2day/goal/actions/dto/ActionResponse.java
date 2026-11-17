package com.aim2day.goal.actions.dto;

import com.aim2day.goal.actions.ActionType;
import com.aim2day.goal.goals.GoalRef;
import com.aim2day.goal.goals.GoalStatus;
import com.aim2day.goal.goals.MonthlyRef;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * 행동 객체(API 명세 §4.9). MAINTENANCE는 goalPath, contributionValue, pathStatus가 null이고 displayColor는 고정 회색이다.
 * placement는 사분면 분류(단위 5) 전까지 항상 UNCLASSIFIED다.
 */
public record ActionResponse(long id, ActionType type, String title, LocalDate performedDate, LocalDate dueDate,
		Integer daysUntilDue, @JsonProperty("isCompleted") boolean isCompleted, Instant completedAt,
		BigDecimal contributionValue, String resultSummary, GoalPathView goalPath, GoalStatus pathStatus,
		String displayColor, Placement placement, long version) {

	/** MAINTENANCE 행동의 표시 색(사용자 확정 결정, 2026-11-17). 명세에 색 값이 없어 회색으로 고정한다. */
	public static final String MAINTENANCE_COLOR = "#9CA3AF";

	public record GoalPathView(GoalRef yearlyGoal, MonthlyRef monthlyGoal, GoalRef weeklyGoal) {
	}

	/** 점 위치. 중요도(x)와 긴급도(y)는 0~100이다. */
	public record Coordinate(int importance, int urgency) {
	}

	/** 분류 상태(§4.9 표). displayed가 null이면 트레이(미분류)다. */
	public record Placement(String state, Coordinate confirmed, Coordinate pending, Coordinate displayed) {

		public static Placement unclassified() {
			return new Placement("UNCLASSIFIED", null, null, null);
		}
	}
}
