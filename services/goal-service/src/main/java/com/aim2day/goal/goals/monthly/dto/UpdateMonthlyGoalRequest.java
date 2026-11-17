package com.aim2day.goal.goals.monthly.dto;

import com.aim2day.goal.goals.GoalStatus;
import com.fasterxml.jackson.annotation.JsonSetter;
import java.util.Optional;

/**
 * PATCH 본문. 필드 없음은 Java null(바꾸지 않음), JSON null은 Optional.empty()다(UpdateYearlyGoalRequest와 같다).
 * description null은 지움, color null은 "부모 색 상속으로 되돌림", title·status null은 400이다.
 * 귀속 월과 부모 연간 목표는 바꿀 수 없으므로 받지 않는다.
 */
public class UpdateMonthlyGoalRequest {

	private Optional<String> title;
	private Optional<String> description;
	private Optional<String> color;
	private Optional<GoalStatus> status;
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

	@JsonSetter("version")
	public void setVersion(Long version) {
		this.version = version;
	}
}
