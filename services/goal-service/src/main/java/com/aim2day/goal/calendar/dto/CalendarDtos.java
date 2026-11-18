package com.aim2day.goal.calendar.dto;

import com.aim2day.goal.calendar.CalendarItemType;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** 이 도메인의 요청·응답 형식 모음. */
public final class CalendarDtos {

	private CalendarDtos() {
	}

	/** 완료 상태 지정. completed는 필수이고 version은 받지 않는다(상태를 지정하는 요청은 멱등이다, API 명세 결정 2). */
	public record CalendarCompletionRequest(Boolean completed) {
	}

	/** 대시보드의 해야 할 일·일정 두 열(API 명세 §4.11). today는 계정 시간대 기준이다. */
	public record CalendarDashboardResponse(LocalDate today, List<CalendarItemResponse> tasks,
			List<CalendarItemResponse> events, List<PastEvent> pastEvents) {

		/** 달력 항목 객체에 daysSinceEnd(오늘 - 종료일)를 더한 지난 일정. */
		public record PastEvent(long id, CalendarItemType itemType, String title, String description, LocalDate startDate,
				LocalDate endDate, @JsonProperty("isCompleted") boolean isCompleted, Instant completedAt,
				CalendarItemResponse.YearlyRef yearlyGoal, long daysUntilDue, long version, long daysSinceEnd) {

			public static PastEvent of(CalendarItemResponse item, long daysSinceEnd) {
				return new PastEvent(item.id(), item.itemType(), item.title(), item.description(), item.startDate(),
						item.endDate(), item.isCompleted(), item.completedAt(), item.yearlyGoal(), item.daysUntilDue(),
						item.version(), daysSinceEnd);
			}
		}
	}

	/**
	 * 달력 항목 객체(API 명세 §4.11). yearlyGoal이 null이면 목표에 연결하지 않았거나 연결된 목표가 삭제 상태인 항목이다.
	 * daysUntilDue는 TASK는 마감일, EVENT는 시작일까지 남은 일수다(지났으면 음수).
	 */
	public record CalendarItemResponse(long id, CalendarItemType itemType, String title, String description,
			LocalDate startDate, LocalDate endDate, @JsonProperty("isCompleted") boolean isCompleted, Instant completedAt,
			YearlyRef yearlyGoal, long daysUntilDue, long version) {

		public record YearlyRef(long id, String title, String color) {
		}
	}

	/** 달력 항목 생성 본문(API 명세 §4.11 POST). 종류별 조건부 규칙이 있어 검증은 서비스가 한다. */
	public record CreateCalendarItemRequest(CalendarItemType itemType, String title, String description,
			LocalDate startDate, LocalDate endDate, Long yearlyGoalId) {
	}

	/**
	 * PATCH 본문. 필드 없음은 Java null(바꾸지 않음), JSON null은 Optional.empty()다(UpdateActionRequest와 같은 setter 방식).
	 * description·endDate·yearlyGoalId의 null은 값을 지움(endDate는 시작일로, yearlyGoalId는 연결 해제)이고,
	 * title·startDate·itemType의 null은 400이다. itemType은 바꿀 수 없지만 받아서 다른 값이면 422로 알린다(같으면 무시).
	 */
	public static class UpdateCalendarItemRequest {

		private Optional<String> title;
		private Optional<String> description;
		private Optional<LocalDate> startDate;
		private Optional<LocalDate> endDate;
		private Optional<Long> yearlyGoalId;
		private Optional<CalendarItemType> itemType;
		private Long version;

		public Optional<String> title() {
			return title;
		}

		public Optional<String> description() {
			return description;
		}

		public Optional<LocalDate> startDate() {
			return startDate;
		}

		public Optional<LocalDate> endDate() {
			return endDate;
		}

		public Optional<Long> yearlyGoalId() {
			return yearlyGoalId;
		}

		public Optional<CalendarItemType> itemType() {
			return itemType;
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

		@JsonSetter("startDate")
		public void setStartDate(Optional<LocalDate> startDate) {
			this.startDate = startDate;
		}

		@JsonSetter("endDate")
		public void setEndDate(Optional<LocalDate> endDate) {
			this.endDate = endDate;
		}

		@JsonSetter("yearlyGoalId")
		public void setYearlyGoalId(Optional<Long> yearlyGoalId) {
			this.yearlyGoalId = yearlyGoalId;
		}

		@JsonSetter("itemType")
		public void setItemType(Optional<CalendarItemType> itemType) {
			this.itemType = itemType;
		}

		@JsonSetter("version")
		public void setVersion(Long version) {
			this.version = version;
		}
	}
}
