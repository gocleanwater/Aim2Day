package com.aim2day.goal.onboarding.dto;

import com.aim2day.goal.actions.ActionType;
import com.fasterxml.jackson.annotation.JsonSetter;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 온보딩 행동 항목(API 명세 §4.9 생성 본문 + weeklyGoalTempId·monthlyGoalTempId).
 * 그 밖의 할 일(MAINTENANCE)은 연결 필드·기여량을 "보내는 것 자체"가 400이라 키가 있었는지를 따로 기억한다
 * (CreateActionRequest와 같은 방식). 값 접근자는 생략·null 모두 null이다.
 */
public class ActionItem {

	private ActionType type;
	private String monthlyGoalTempId;
	private Long monthlyGoalId;
	private String weeklyGoalTempId;
	private Long weeklyGoalId;
	private String title;
	private LocalDate performedDate;
	private LocalDate dueDate;
	private BigDecimal contributionValue;
	private boolean monthlyGoalTempIdPresent;
	private boolean monthlyGoalIdPresent;
	private boolean weeklyGoalTempIdPresent;
	private boolean weeklyGoalIdPresent;
	private boolean contributionValuePresent;

	public ActionType type() {
		return type;
	}

	public String monthlyGoalTempId() {
		return monthlyGoalTempId;
	}

	public Long monthlyGoalId() {
		return monthlyGoalId;
	}

	public String weeklyGoalTempId() {
		return weeklyGoalTempId;
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

	public boolean monthlyGoalTempIdPresent() {
		return monthlyGoalTempIdPresent;
	}

	public boolean monthlyGoalIdPresent() {
		return monthlyGoalIdPresent;
	}

	public boolean weeklyGoalTempIdPresent() {
		return weeklyGoalTempIdPresent;
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

	@JsonSetter("monthlyGoalTempId")
	public void setMonthlyGoalTempId(String monthlyGoalTempId) {
		this.monthlyGoalTempId = monthlyGoalTempId;
		this.monthlyGoalTempIdPresent = true;
	}

	@JsonSetter("monthlyGoalId")
	public void setMonthlyGoalId(Long monthlyGoalId) {
		this.monthlyGoalId = monthlyGoalId;
		this.monthlyGoalIdPresent = true;
	}

	@JsonSetter("weeklyGoalTempId")
	public void setWeeklyGoalTempId(String weeklyGoalTempId) {
		this.weeklyGoalTempId = weeklyGoalTempId;
		this.weeklyGoalTempIdPresent = true;
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
