package com.aim2day.goal.goals.yearly.dto;

import com.aim2day.goal.common.time.UtcTime;
import com.aim2day.goal.goals.GoalStatus;
import com.aim2day.goal.goals.criteria.CriteriaSummary;
import com.aim2day.goal.goals.yearly.YearlyGoal;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.time.LocalDate;

/** 연간 목표 응답 객체(API 명세 §4.3). */
public record YearlyGoalResponse(long id, int targetYear, String title, String description, String color,
		String displayColor, int displayOrder, GoalStatus status, GoalStatus pathStatus,
		@JsonProperty("isDelayed") boolean isDelayed, CriteriaSummary criteria, long version,
		Instant createdAt, Instant updatedAt) {

	/** today는 호출자가 계정 시간대로 계산해 넘긴다(클라이언트 시계는 쓰지 않는다). */
	public static YearlyGoalResponse of(YearlyGoal goal, CriteriaSummary criteria, LocalDate today) {
		GoalStatus status = goal.getStatus();
		// 연간 목표는 최상위라 자기 상태가 곧 경로 상태다. 보류·중단일 때만 값이 있다(GOAL-009).
		GoalStatus pathStatus = status == GoalStatus.ON_HOLD || status == GoalStatus.DROPPED ? status : null;
		// 종료일(해당 연도 12월 31일)이 오늘보다 앞서고 완료가 아니면 지연. 저장 상태는 바꾸지 않는다(DASH-004).
		boolean delayed = LocalDate.of(goal.getTargetYear(), 12, 31).isBefore(today)
				&& status != GoalStatus.COMPLETED;
		return new YearlyGoalResponse(goal.getId(), goal.getTargetYear(), goal.getTitle(), goal.getDescription(),
				goal.getColor(), goal.getColor(), goal.getDisplayOrder(), status, pathStatus, delayed, criteria,
				goal.getVersion(), UtcTime.toInstant(goal.getCreatedAt()), UtcTime.toInstant(goal.getUpdatedAt()));
	}
}
