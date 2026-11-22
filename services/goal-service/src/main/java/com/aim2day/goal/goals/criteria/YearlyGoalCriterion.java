package com.aim2day.goal.goals.criteria;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/**
 * 연간 목표 달성 기준(ERD 3.2-5). 자체 version이 없고 부모 yearly_goals의 행 잠금과 version으로 보호한다(ERD 결정 15).
 */
@Entity
@Table(name = "yearly_goal_criteria")
public class YearlyGoalCriterion {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "yearly_goal_id", nullable = false, updatable = false)
	private Long yearlyGoalId;

	@Column(name = "criterion_text", nullable = false, length = 255)
	private String text;

	@Column(nullable = false)
	private int weight;

	@Column(name = "is_achieved", nullable = false)
	private boolean achieved;

	@Column(name = "achieved_at")
	private LocalDateTime achievedAt;

	@Column(name = "is_baseline_achieved", nullable = false)
	private boolean baselineAchieved;

	// created_at, updated_at은 DB 기본값이 채우며 응답에 쓰지 않으므로 매핑하지 않는다(UserSetting과 같다).

	protected YearlyGoalCriterion() {
	}

	public YearlyGoalCriterion(Long yearlyGoalId, String text, int weight) {
		this.yearlyGoalId = yearlyGoalId;
		this.text = text;
		this.weight = weight;
	}

	public Long getId() {
		return id;
	}

	public Long getYearlyGoalId() {
		return yearlyGoalId;
	}

	public String getText() {
		return text;
	}

	public int getWeight() {
		return weight;
	}

	public boolean isAchieved() {
		return achieved;
	}

	public LocalDateTime getAchievedAt() {
		return achievedAt;
	}

	public boolean isBaselineAchieved() {
		return baselineAchieved;
	}

	/** 기준 개정: 내용과 배점만 바꾼다. 달성 여부는 그대로 유지한다(API 명세 §4.4). */
	public void revise(String text, int weight) {
		this.text = text;
		this.weight = weight;
	}

	/** 최초 확정 때 이미 달성한 것으로 표시한다. */
	public void markBaselineAchieved(LocalDateTime now) {
		this.achieved = true;
		this.baselineAchieved = true;
		this.achievedAt = now;
	}

	/** 달성 처리하고 달성 시각을 now로 기록한다. */
	public void achieve(LocalDateTime now) {
		this.achieved = true;
		this.achievedAt = now;
	}

	/** 달성을 취소하고 달성 시각을 지운다. */
	public void unachieve() {
		this.achieved = false;
		this.achievedAt = null;
	}
}
