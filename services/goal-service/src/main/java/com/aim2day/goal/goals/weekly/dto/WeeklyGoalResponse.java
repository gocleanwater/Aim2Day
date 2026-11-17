package com.aim2day.goal.goals.weekly.dto;

import com.aim2day.goal.goals.GoalPath;
import com.aim2day.goal.goals.GoalRef;
import com.aim2day.goal.goals.GoalStatus;
import com.aim2day.goal.goals.MonthlyRef;
import com.aim2day.goal.goals.ProgressView;
import com.aim2day.goal.goals.monthly.MonthlyGoal;
import com.aim2day.goal.goals.weekly.MeasurementType;
import com.aim2day.goal.goals.weekly.WeeklyGoal;
import com.aim2day.goal.goals.yearly.YearlyGoal;
import com.aim2day.goal.progress.ActionCounts;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;

/** 주간 목표 응답 객체(API 명세 §4.6). 체크형이면 targetValue, currentValue, isTargetReached가 null이다. */
public record WeeklyGoalResponse(long id, Path path, LocalDate startDate, LocalDate endDate, String title,
		String description, String color, String displayColor, MeasurementType measurementType,
		BigDecimal targetValue, BigDecimal currentValue, @JsonProperty("isTargetReached") Boolean isTargetReached,
		BigDecimal weight, GoalStatus status, GoalStatus pathStatus, @JsonProperty("isDelayed") boolean isDelayed,
		ProgressView progress, ActionCountsView actionCounts, long version) {

	public record Path(GoalRef yearlyGoal, MonthlyRef monthlyGoal) {
	}

	public record ActionCountsView(int total, int completed) {
	}

	public static WeeklyGoalResponse of(WeeklyGoal w, MonthlyGoal m, YearlyGoal y, ActionCounts counts,
			LocalDate today) {
		boolean numeric = w.getMeasurementType() == MeasurementType.NUMERIC;
		// 수치형은 목표값이 있으므로 0%도 기록이다. 체크형은 연결 행동(분모)이 0개면 기록 없음이다.
		ProgressView progress = numeric || counts.total() > 0 ? ProgressView.recorded(w.getProgressRate())
				: ProgressView.noRecord();
		// 완료 색상은 반올림한 진행률이 아니라 원래 값 비교로 정한다(ERD §4.3).
		Boolean reached = numeric ? w.getCurrentValue().compareTo(w.getTargetValue()) >= 0 : null;
		return new WeeklyGoalResponse(w.getId(),
				new Path(new GoalRef(y.getId(), y.getTitle()),
						new MonthlyRef(m.getId(), m.getTitle(), YearMonth.from(m.getStartDate()).toString())),
				w.getStartDate(), w.getEndDate(), w.getTitle(), w.getDescription(), w.getColor(),
				GoalPath.displayColor(w.getColor(), m.getColor(), y.getColor()), w.getMeasurementType(),
				numeric ? w.getTargetValue() : null, numeric ? w.getCurrentValue() : null, reached, w.getWeight(),
				w.getStatus(), GoalPath.pathStatus(w.getStatus(), m.getStatus(), y.getStatus()),
				GoalPath.isDelayed(w.getEndDate(), w.getStatus(), today), progress,
				new ActionCountsView(counts.total(), counts.completed()), w.getVersion());
	}
}
