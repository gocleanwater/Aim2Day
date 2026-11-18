package com.aim2day.goal.reports;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * 기간별 직접 회고(ERD 3.6-13). (user_id, period_type, period_value)당 1행이다.
 * 두 인스턴스가 같은 회고를 동시에 고치지 못하게 @Version으로 낙관적 락을 건다.
 */
@Entity
@Table(name = "period_reviews")
public class PeriodReview {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "user_id", nullable = false, updatable = false)
	private Long userId;

	@Column(name = "period_type", nullable = false, updatable = false, length = 10)
	private String periodType;

	@Column(name = "period_value", nullable = false, updatable = false, length = 20)
	private String periodValue;

	@Column(columnDefinition = "TEXT")
	private String improvements;

	@Column(name = "next_focus", columnDefinition = "TEXT")
	private String nextFocus;

	@Version
	private Long version;

	protected PeriodReview() {
	}

	public PeriodReview(Long userId, PeriodType periodType, String periodValue, String improvements, String nextFocus) {
		this.userId = userId;
		this.periodType = periodType.name();
		this.periodValue = periodValue;
		this.improvements = improvements;
		this.nextFocus = nextFocus;
	}

	public Long getId() {
		return id;
	}

	public String getImprovements() {
		return improvements;
	}

	public String getNextFocus() {
		return nextFocus;
	}

	public Long getVersion() {
		return version;
	}

	/** 전체 저장: 두 필드를 모두 덮어쓴다(null이면 null로 저장). */
	public void change(String improvements, String nextFocus) {
		this.improvements = improvements;
		this.nextFocus = nextFocus;
	}
}
