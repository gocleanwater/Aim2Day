package com.aim2day.goal.actions.dto;

import com.aim2day.goal.actions.ActionSort;
import com.aim2day.goal.actions.ActionType;
import com.aim2day.goal.goals.GoalRef;
import com.aim2day.goal.goals.GoalStatus;
import com.aim2day.goal.goals.MonthlyRef;
import com.aim2day.goal.goals.ProgressView;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** 이 도메인의 요청·응답 형식 모음. */
public final class ActionDtos {

	private ActionDtos() {
	}

	/** 완료 응답. MAINTENANCE이면 weeklyGoal, monthlyGoal이 null이다. 체크형이면 currentValue, isTargetReached가 null이다. */
	public record ActionCompletionResponse(ActionResponse action, WeeklyView weeklyGoal, MonthlyView monthlyGoal) {

		public record WeeklyView(long id, ProgressView progress, BigDecimal currentValue,
				@JsonProperty("isTargetReached") Boolean isTargetReached) {
		}

		public record MonthlyView(long id, ProgressView progress) {
		}
	}

	/** 행동 목록 응답(API 명세 §4.9). pastIncomplete는 date가 오늘일 때만 채우고 아니면 null이다. */
	public record ActionListResponse(LocalDate date, ActionSort sort, Summary summary, Classification classification,
			List<ActionResponse> items, PastIncomplete pastIncomplete) {

		public record Counts(int total, int completed) {
		}

		public record Summary(Counts goal, Counts maintenance) {
		}

		public record Classification(int unclassified, int unconfirmedChanges, int confirmed, boolean confirmedToday) {
		}

		public record PastIncomplete(int count, List<ActionResponse> items) {
		}
	}

	/**
	 * 행동 객체(API 명세 §4.9). MAINTENANCE는 goalPath, contributionValue, pathStatus가 null이고 displayColor는 고정 회색이다.
	 * placement는 수행일에 해당하는 좌표 행에서 만든다(quadrant.PlacementViews).
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

	/** 완료 상태 지정. completed는 필수이고 memo는 진행 기록의 메모다(최대 255자, 생략하면 서버가 채운다). */
	public record CompletionRequest(Boolean completed, String memo) {
	}

	/**
	 * 행동 생성 본문(API 명세 §4.9). 조건부 규칙(GOAL은 연결 대상 필수, MAINTENANCE는 금지)이 많아 검증은 서비스가 한다.
	 * MAINTENANCE는 monthlyGoalId·weeklyGoalId·contributionValue를 "보내는 것 자체"가 400이므로, 값이 null이어도
	 * 키가 있었는지를 구분해야 한다. 세터가 불린 필드는 present 플래그를 켠다(생략과 명시적 null을 구분).
	 * 값 접근자는 생략·null 모두 null을 돌려준다(GOAL 쪽 기존 의미 유지).
	 */
	public static class CreateActionRequest {

		private ActionType type;
		private Long monthlyGoalId;
		private Long weeklyGoalId;
		private String title;
		private LocalDate performedDate;
		private LocalDate dueDate;
		private BigDecimal contributionValue;
		private boolean monthlyGoalIdPresent;
		private boolean weeklyGoalIdPresent;
		private boolean contributionValuePresent;

		public ActionType type() {
			return type;
		}

		public Long monthlyGoalId() {
			return monthlyGoalId;
		}

		public Long weeklyGoalId() {
			return weeklyGoalId;
		}

		public String title() {
			return title;
		}

		public LocalDate performedDate() {
			return performedDate;
		}

		public LocalDate dueDate() {
			return dueDate;
		}

		public BigDecimal contributionValue() {
			return contributionValue;
		}

		public boolean monthlyGoalIdPresent() {
			return monthlyGoalIdPresent;
		}

		public boolean weeklyGoalIdPresent() {
			return weeklyGoalIdPresent;
		}

		public boolean contributionValuePresent() {
			return contributionValuePresent;
		}

		@JsonSetter("type")
		public void setType(ActionType type) {
			this.type = type;
		}

		@JsonSetter("monthlyGoalId")
		public void setMonthlyGoalId(Long monthlyGoalId) {
			this.monthlyGoalId = monthlyGoalId;
			this.monthlyGoalIdPresent = true;
		}

		@JsonSetter("weeklyGoalId")
		public void setWeeklyGoalId(Long weeklyGoalId) {
			this.weeklyGoalId = weeklyGoalId;
			this.weeklyGoalIdPresent = true;
		}

		@JsonSetter("title")
		public void setTitle(String title) {
			this.title = title;
		}

		@JsonSetter("performedDate")
		public void setPerformedDate(LocalDate performedDate) {
			this.performedDate = performedDate;
		}

		@JsonSetter("dueDate")
		public void setDueDate(LocalDate dueDate) {
			this.dueDate = dueDate;
		}

		@JsonSetter("contributionValue")
		public void setContributionValue(BigDecimal contributionValue) {
			this.contributionValue = contributionValue;
			this.contributionValuePresent = true;
		}
	}

	/**
	 * PATCH 본문. 필드 없음은 Java null(바꾸지 않음), JSON null은 Optional.empty()다(UpdateWeeklyGoalRequest와 같은 setter 방식).
	 * dueDate, resultSummary, contributionValue의 null은 값을 지우고, title, performedDate, weeklyGoalId, type의 null은 400이다.
	 * type은 바꿀 수 없지만 받아서 다른 값이면 400으로 알린다(같은 값이면 무시).
	 */
	public static class UpdateActionRequest {

		private Optional<String> title;
		private Optional<LocalDate> performedDate;
		private Optional<LocalDate> dueDate;
		private Optional<String> resultSummary;
		private Optional<Long> weeklyGoalId;
		private Optional<BigDecimal> contributionValue;
		private Optional<ActionType> type;
		private Long version;

		public Optional<String> title() {
			return title;
		}

		public Optional<LocalDate> performedDate() {
			return performedDate;
		}

		public Optional<LocalDate> dueDate() {
			return dueDate;
		}

		public Optional<String> resultSummary() {
			return resultSummary;
		}

		public Optional<Long> weeklyGoalId() {
			return weeklyGoalId;
		}

		public Optional<BigDecimal> contributionValue() {
			return contributionValue;
		}

		public Optional<ActionType> type() {
			return type;
		}

		public Long version() {
			return version;
		}

		@JsonSetter("title")
		public void setTitle(Optional<String> title) {
			this.title = title;
		}

		@JsonSetter("performedDate")
		public void setPerformedDate(Optional<LocalDate> performedDate) {
			this.performedDate = performedDate;
		}

		@JsonSetter("dueDate")
		public void setDueDate(Optional<LocalDate> dueDate) {
			this.dueDate = dueDate;
		}

		@JsonSetter("resultSummary")
		public void setResultSummary(Optional<String> resultSummary) {
			this.resultSummary = resultSummary;
		}

		@JsonSetter("weeklyGoalId")
		public void setWeeklyGoalId(Optional<Long> weeklyGoalId) {
			this.weeklyGoalId = weeklyGoalId;
		}

		@JsonSetter("contributionValue")
		public void setContributionValue(Optional<BigDecimal> contributionValue) {
			this.contributionValue = contributionValue;
		}

		@JsonSetter("type")
		public void setType(Optional<ActionType> type) {
			this.type = type;
		}

		@JsonSetter("version")
		public void setVersion(Long version) {
			this.version = version;
		}
	}
}
