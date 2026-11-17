package com.aim2day.goal.actions.dto;

import com.aim2day.goal.actions.ActionType;
import com.fasterxml.jackson.annotation.JsonSetter;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

/**
 * PATCH 본문. 필드 없음은 Java null(바꾸지 않음), JSON null은 Optional.empty()다(UpdateWeeklyGoalRequest와 같은 setter 방식).
 * dueDate, resultSummary, contributionValue의 null은 값을 지우고, title, performedDate, weeklyGoalId, type의 null은 400이다.
 * type은 바꿀 수 없지만 받아서 다른 값이면 400으로 알린다(같은 값이면 무시).
 */
public class UpdateActionRequest {

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
