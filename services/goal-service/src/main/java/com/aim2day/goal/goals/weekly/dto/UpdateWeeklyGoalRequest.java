package com.aim2day.goal.goals.weekly.dto;

import com.aim2day.goal.goals.GoalStatus;
import com.aim2day.goal.goals.weekly.MeasurementType;
import com.fasterxml.jackson.annotation.JsonSetter;
import java.math.BigDecimal;
import java.util.Optional;

/**
 * PATCH 본문. 필드 없음은 Java null(바꾸지 않음), JSON null은 Optional.empty()다(UpdateYearlyGoalRequest와 같은 setter 방식).
 * description null은 지움, color null은 부모 색 상속으로 되돌림이고 나머지 필드의 null은 400이다.
 * measurementType은 바꿀 수 없지만 받아서 다른 값이면 422로 알린다(같은 값이면 무시).
 */
public class UpdateWeeklyGoalRequest {

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
