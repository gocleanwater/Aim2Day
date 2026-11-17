package com.aim2day.goal.goals.monthly.dto;

import com.aim2day.goal.goals.GoalPath;
import com.aim2day.goal.goals.GoalRef;
import com.aim2day.goal.goals.GoalStatus;
import com.aim2day.goal.goals.ProgressView;
import com.aim2day.goal.goals.monthly.MonthlyGoal;
import com.aim2day.goal.goals.yearly.YearlyGoal;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDate;
import java.time.YearMonth;

/** 월간 목표 응답 객체(API 명세 §4.5). */
public record MonthlyGoalResponse(long id, GoalRef yearlyGoal, String month, LocalDate startDate, LocalDate endDate,
		String title, String description, String color, String displayColor, GoalStatus status, GoalStatus pathStatus,
		@JsonProperty("isDelayed") boolean isDelayed, ProgressView progress, int weeklyGoalCount, long version) {

	public static MonthlyGoalResponse of(MonthlyGoal m, YearlyGoal y, int weeklyGoalCount, LocalDate today) {
		// 하위 주간 목표가 하나도 없을 때만 "기록 없음"이다. 있으면 0%도 기록이다(ERD §4.3).
		ProgressView progress = weeklyGoalCount == 0 ? ProgressView.noRecord()
				: ProgressView.recorded(m.getProgressRate());
		return new MonthlyGoalResponse(m.getId(), new GoalRef(y.getId(), y.getTitle()),
				YearMonth.from(m.getStartDate()).toString(), m.getStartDate(), m.getEndDate(), m.getTitle(),
				m.getDescription(), m.getColor(), GoalPath.displayColor(m.getColor(), y.getColor()), m.getStatus(),
				GoalPath.pathStatus(m.getStatus(), y.getStatus()),
				GoalPath.isDelayed(m.getEndDate(), m.getStatus(), today), progress, weeklyGoalCount, m.getVersion());
	}
}
