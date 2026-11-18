package com.aim2day.goal.goals.weekly.dto;

import com.aim2day.goal.actions.dto.ActionDtos.ActionResponse;
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
import com.fasterxml.jackson.annotation.JsonSetter;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;

/** 이 도메인의 요청·응답 형식 모음. */
public final class WeeklyGoalDtos {

	private WeeklyGoalDtos() {
	}

	/**
	 * 기간은 받지 않는다(생성 시점의 이번 주로 서버가 정한다). measurementType 생략은 CHECKLIST, weight 생략은 1이다.
	 * 검증은 서비스가 한다(NUMERIC일 때만 targetValue 필수 같은 조건부 규칙이라서).
	 */
	public record CreateWeeklyGoalRequest(Long monthlyGoalId, String title, String description, String color,
			MeasurementType measurementType, BigDecimal targetValue, BigDecimal weight) {
	}

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

	/**
	 * PATCH 본문. 필드 없음은 Java null(바꾸지 않음), JSON null은 Optional.empty()다(UpdateYearlyGoalRequest와 같은 setter 방식).
	 * description null은 지움, color null은 부모 색 상속으로 되돌림이고 나머지 필드의 null은 400이다.
	 * measurementType은 바꿀 수 없지만 받아서 다른 값이면 422로 알린다(같은 값이면 무시).
	 */
	public static class UpdateWeeklyGoalRequest {

		private Optional<String> title;
		private Optional<String> description;
		private Optional<String> color;
		private Optional<GoalStatus> status;
		private Optional<BigDecimal> targetValue;
		private Optional<BigDecimal> weight;
		private Optional<Long> monthlyGoalId;
		private Optional<MeasurementType> measurementType;
		private Long version;

		public Optional<String> title() {
			return title;
		}

		public Optional<String> description() {
			return description;
		}

		public Optional<String> color() {
			return color;
		}

		public Optional<GoalStatus> status() {
			return status;
		}

		public Optional<BigDecimal> targetValue() {
			return targetValue;
		}

		public Optional<BigDecimal> weight() {
			return weight;
		}

		public Optional<Long> monthlyGoalId() {
			return monthlyGoalId;
		}

		public Optional<MeasurementType> measurementType() {
			return measurementType;
		}

		public Long version() {
			return version;
		}

		@JsonSetter("title")
		public void setTitle(Optional<String> title) {
			this.title = title;
		}

		@JsonSetter("description")
		public void setDescription(Optional<String> description) {
			this.description = description;
		}

		@JsonSetter("color")
		public void setColor(Optional<String> color) {
			this.color = color;
		}

		@JsonSetter("status")
		public void setStatus(Optional<GoalStatus> status) {
			this.status = status;
		}

		@JsonSetter("targetValue")
		public void setTargetValue(Optional<BigDecimal> targetValue) {
			this.targetValue = targetValue;
		}

		@JsonSetter("weight")
		public void setWeight(Optional<BigDecimal> weight) {
			this.weight = weight;
		}

		@JsonSetter("monthlyGoalId")
		public void setMonthlyGoalId(Optional<Long> monthlyGoalId) {
			this.monthlyGoalId = monthlyGoalId;
		}

		@JsonSetter("measurementType")
		public void setMeasurementType(Optional<MeasurementType> measurementType) {
			this.measurementType = measurementType;
		}

		@JsonSetter("version")
		public void setVersion(Long version) {
			this.version = version;
		}
	}

	/** 주간 목표 객체에 연결 행동 actions(§4.9 행동 객체)를 더한 응답(§4.6). 삭제되지 않은 행동만 수행일, id 순으로 담는다. */
	public record WeeklyGoalDetailResponse(long id, WeeklyGoalResponse.Path path, LocalDate startDate, LocalDate endDate,
			String title, String description, String color, String displayColor, MeasurementType measurementType,
			BigDecimal targetValue, BigDecimal currentValue, @JsonProperty("isTargetReached") Boolean isTargetReached,
			BigDecimal weight, GoalStatus status, GoalStatus pathStatus, @JsonProperty("isDelayed") boolean isDelayed,
			ProgressView progress, WeeklyGoalResponse.ActionCountsView actionCounts, long version,
			List<ActionResponse> actions) {

		public static WeeklyGoalDetailResponse of(WeeklyGoalResponse w, List<ActionResponse> actions) {
			return new WeeklyGoalDetailResponse(w.id(), w.path(), w.startDate(), w.endDate(), w.title(),
					w.description(), w.color(), w.displayColor(), w.measurementType(), w.targetValue(),
					w.currentValue(), w.isTargetReached(), w.weight(), w.status(), w.pathStatus(), w.isDelayed(),
					w.progress(), w.actionCounts(), w.version(), actions);
		}
	}

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
}
