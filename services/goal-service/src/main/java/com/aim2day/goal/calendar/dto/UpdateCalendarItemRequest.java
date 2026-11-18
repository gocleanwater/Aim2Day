package com.aim2day.goal.calendar.dto;

import com.aim2day.goal.calendar.CalendarItemType;
import com.fasterxml.jackson.annotation.JsonSetter;
import java.time.LocalDate;
import java.util.Optional;

/**
 * PATCH 본문. 필드 없음은 Java null(바꾸지 않음), JSON null은 Optional.empty()다(UpdateActionRequest와 같은 setter 방식).
 * description·endDate·yearlyGoalId의 null은 값을 지움(endDate는 시작일로, yearlyGoalId는 연결 해제)이고,
 * title·startDate·itemType의 null은 400이다. itemType은 바꿀 수 없지만 받아서 다른 값이면 422로 알린다(같으면 무시).
 */
public class UpdateCalendarItemRequest {

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
