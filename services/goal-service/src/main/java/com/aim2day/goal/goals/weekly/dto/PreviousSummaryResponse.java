package com.aim2day.goal.goals.weekly.dto;

import com.aim2day.goal.goals.GoalRef;
import com.aim2day.goal.goals.ProgressView;
import java.time.LocalDate;
import java.util.List;

/** 직전 주 요약(API 명세 §4.6). 직전 주에 걸친 주간 목표가 없으면 weeklyGoals가 빈 배열이다. */
public record PreviousSummaryResponse(LocalDate startDate, LocalDate endDate, List<WeeklyEntry> weeklyGoals) {

	public record Path(GoalRef yearlyGoal, GoalRef monthlyGoal) {
	}

	public record ActionBrief(long id, String title, LocalDate performedDate) {
	}

	public record WeeklyEntry(long id, String title, Path path, ProgressView progress,
			List<ActionBrief> completedActions, List<ActionBrief> remainingActions) {
	}
}
