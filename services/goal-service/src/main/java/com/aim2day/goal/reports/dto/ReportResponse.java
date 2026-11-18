package com.aim2day.goal.reports.dto;

import com.aim2day.goal.actions.dto.ActionResponse.GoalPathView;
import com.aim2day.goal.reports.PeriodType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** 기간 리포트(API 명세 §4.12). 기록이 없으면 0과 빈 배열이다(추정하지 않는다). review가 없으면 null이다. */
public record ReportResponse(PeriodType periodType, String period, LocalDate startDate, LocalDate endDate,
		Summary summary, List<ReportAction> actions, List<ReportResult> results, List<GoalProgressItem> goalProgress,
		ReviewResponse review) {

	public record Summary(int completedActionCount, int activeDayCount) {
	}

	public record ReportAction(long id, String title, LocalDate performedDate, GoalPathView goalPath,
			String resultSummary) {
	}

	public record ReportResult(long actionId, LocalDate performedDate, String resultSummary) {
	}

	/** 연간 목표 하나의 달성률 변화. NOT_CONFIGURED면 숫자·라벨이 모두 null이다. */
	public record GoalProgressItem(long yearlyGoalId, String title, String color, State state, StartBase startBase,
			String startLabel, String endLabel, BigDecimal startRate, BigDecimal endRate, BigDecimal changePp,
			LocalDate observedFrom, String note) {
	}

	public enum State {
		COMPARABLE, CRITERIA_REVISED, NOT_CONFIGURED
	}

	public enum StartBase {
		PREVIOUS_PERIOD_END, INITIAL_BASELINE
	}
}
