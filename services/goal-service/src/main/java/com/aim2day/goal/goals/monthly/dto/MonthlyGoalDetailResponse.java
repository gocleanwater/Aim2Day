package com.aim2day.goal.goals.monthly.dto;

import com.aim2day.goal.goals.GoalRef;
import com.aim2day.goal.goals.GoalStatus;
import com.aim2day.goal.goals.ProgressView;
import com.aim2day.goal.goals.weekly.dto.WeeklyGoalResponse;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDate;
import java.util.List;

/**
 * 월간 목표 객체에 하위 weeklyGoals를 더한 응답. 월간 목표 상세와 연간 목표 상세의 트리가 같은 모양이다.
 * 월간 필드를 감싸지 않고 같은 수준에 펼쳐 명세 예시(월간 목표 객체 안에 weeklyGoals 배열)와 맞춘다.
 */
public record MonthlyGoalDetailResponse(long id, GoalRef yearlyGoal, String month, LocalDate startDate,
		LocalDate endDate, String title, String description, String color, String displayColor, GoalStatus status,
		GoalStatus pathStatus, @JsonProperty("isDelayed") boolean isDelayed, ProgressView progress,
		int weeklyGoalCount, long version, List<WeeklyGoalResponse> weeklyGoals) {

	public static MonthlyGoalDetailResponse of(MonthlyGoalResponse m, List<WeeklyGoalResponse> weeklyGoals) {
		return new MonthlyGoalDetailResponse(m.id(), m.yearlyGoal(), m.month(), m.startDate(), m.endDate(),
				m.title(), m.description(), m.color(), m.displayColor(), m.status(), m.pathStatus(), m.isDelayed(),
				m.progress(), m.weeklyGoalCount(), m.version(), weeklyGoals);
	}
}
