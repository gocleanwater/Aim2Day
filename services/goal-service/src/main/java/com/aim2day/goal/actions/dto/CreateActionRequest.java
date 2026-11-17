package com.aim2day.goal.actions.dto;

import com.aim2day.goal.actions.ActionType;
import com.fasterxml.jackson.annotation.JsonSetter;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 행동 생성 본문(API 명세 §4.9). 조건부 규칙(GOAL은 연결 대상 필수, MAINTENANCE는 금지)이 많아 검증은 서비스가 한다.
 * MAINTENANCE는 monthlyGoalId·weeklyGoalId·contributionValue를 "보내는 것 자체"가 400이므로, 값이 null이어도
 * 키가 있었는지를 구분해야 한다. 세터가 불린 필드는 present 플래그를 켠다(생략과 명시적 null을 구분).
 * 값 접근자는 생략·null 모두 null을 돌려준다(GOAL 쪽 기존 의미 유지).
 */
public class CreateActionRequest {

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
