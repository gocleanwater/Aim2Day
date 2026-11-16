package com.aim2day.goal.progress;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 목표 진행률 변경 이력(ERD 3.5-11). 추가만 하고 고치지 않으므로 version이 없고 모든 컬럼이 updatable=false다.
 * target_id는 폴리모픽 참조라 FK가 없다. 목표가 삭제돼도 이력이 남는다.
 */
@Entity
@Table(name = "goal_progress_logs")
public class GoalProgressLog {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "user_id", nullable = false, updatable = false)
	private Long userId;

	@Enumerated(EnumType.STRING)
	@Column(name = "target_type", nullable = false, updatable = false, length = 20)
	private ProgressTargetType targetType;

	@Column(name = "target_id", nullable = false, updatable = false)
	private Long targetId;

	@Column(name = "previous_rate", nullable = false, updatable = false, precision = 5, scale = 2)
	private BigDecimal previousRate;

	@Column(name = "new_rate", nullable = false, updatable = false, precision = 5, scale = 2)
	private BigDecimal newRate;

	@Enumerated(EnumType.STRING)
	@Column(name = "change_type", nullable = false, updatable = false, length = 30)
	private ProgressChangeType changeType;

	@Column(name = "log_date", nullable = false, updatable = false)
	private LocalDate logDate;

	@Column(updatable = false)
	private String memo;

	/** DB 기본값이 채운 UTC 시각. 조회에서만 읽는다. */
	@Column(name = "created_at", insertable = false, updatable = false)
	private LocalDateTime createdAt;

	protected GoalProgressLog() {
	}

	public GoalProgressLog(Long userId, ProgressTargetType targetType, Long targetId, BigDecimal previousRate,
			BigDecimal newRate, ProgressChangeType changeType, LocalDate logDate, String memo) {
		this.userId = userId;
		this.targetType = targetType;
		this.targetId = targetId;
		this.previousRate = previousRate;
		this.newRate = newRate;
		this.changeType = changeType;
		this.logDate = logDate;
		this.memo = memo;
	}

	public Long getId() {
		return id;
	}

	public Long getUserId() {
		return userId;
	}

	public ProgressTargetType getTargetType() {
		return targetType;
	}

	public Long getTargetId() {
		return targetId;
	}

	public BigDecimal getPreviousRate() {
		return previousRate;
	}

	public BigDecimal getNewRate() {
		return newRate;
	}

	public ProgressChangeType getChangeType() {
		return changeType;
	}

	public LocalDate getLogDate() {
		return logDate;
	}

	public String getMemo() {
		return memo;
	}

	public LocalDateTime getCreatedAt() {
		return createdAt;
	}
}
