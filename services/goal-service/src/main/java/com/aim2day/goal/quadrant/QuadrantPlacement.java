package com.aim2day.goal.quadrant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 일일 사분면 좌표(ERD 3.3-9). 행이 없으면 미분류다.
 * 낙관적 락(@Version)을 두지 않는다. 이 테이블의 쓰기는 모두 users 행 잠금 아래에서 직렬화되고(ERD 결정 17),
 * UNIQUE (action_id, classification_date)가 중복 행을 막는다.
 * created_at·updated_at은 DB 기본값에 맡기고 매핑하지 않는다.
 */
@Entity
@Table(name = "action_quadrant_placements")
public class QuadrantPlacement {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "action_id", nullable = false, updatable = false)
	private Long actionId;

	@Column(name = "classification_date", nullable = false, updatable = false)
	private LocalDate classificationDate;

	@Column(name = "importance_x")
	private Integer confirmedImportance;

	@Column(name = "urgency_y")
	private Integer confirmedUrgency;

	@Column(name = "confirmed_at")
	private LocalDateTime confirmedAt;

	@Column(name = "pending_importance_x")
	private Integer pendingImportance;

	@Column(name = "pending_urgency_y")
	private Integer pendingUrgency;

	@Column(name = "is_pending_unclassify", nullable = false)
	private boolean pendingUnclassify;

	protected QuadrantPlacement() {
	}

	public QuadrantPlacement(Long actionId, LocalDate classificationDate) {
		this.actionId = actionId;
		this.classificationDate = classificationDate;
	}

	public Long getId() {
		return id;
	}

	public Long getActionId() {
		return actionId;
	}

	public LocalDate getClassificationDate() {
		return classificationDate;
	}

	public Integer getConfirmedImportance() {
		return confirmedImportance;
	}

	public Integer getConfirmedUrgency() {
		return confirmedUrgency;
	}

	public Integer getPendingImportance() {
		return pendingImportance;
	}

	public Integer getPendingUrgency() {
		return pendingUrgency;
	}

	public boolean isPendingUnclassify() {
		return pendingUnclassify;
	}

	public boolean hasConfirmed() {
		return confirmedImportance != null;
	}

	public boolean hasPending() {
		return pendingImportance != null;
	}

	/** 미확정 좌표를 둔다. 되돌리기 대기였다면 그 대기를 푼다. */
	public void placePending(int importance, int urgency) {
		this.pendingImportance = importance;
		this.pendingUrgency = urgency;
		this.pendingUnclassify = false;
	}

	/** 확정 좌표가 있는 점을 트레이로 되돌리는 미확정 변경(되돌리기 대기). */
	public void markPendingUnclassify() {
		this.pendingImportance = null;
		this.pendingUrgency = null;
		this.pendingUnclassify = true;
	}

	/** 변경 취소: 미확정 좌표와 되돌리기 대기만 지운다. 확정 좌표는 그대로다. */
	public void clearPending() {
		this.pendingImportance = null;
		this.pendingUrgency = null;
		this.pendingUnclassify = false;
	}

	/** 확정: 미확정 좌표를 확정 좌표로 옮기고 확정 시각을 기록한다. */
	public void confirmPending(LocalDateTime now) {
		this.confirmedImportance = pendingImportance;
		this.confirmedUrgency = pendingUrgency;
		this.confirmedAt = now;
		clearPending();
	}
}
