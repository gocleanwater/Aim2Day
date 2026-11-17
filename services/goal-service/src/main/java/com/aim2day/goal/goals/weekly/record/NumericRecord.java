package com.aim2day.goal.goals.weekly.record;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

/**
 * 수치형 주간 목표의 실적 기록(ERD 3.5-12). 추가만 하고 고치지 않으며(잘못 입력하면 삭제 후 다시 기록),
 * 자체 version이 없다(ERD 결정 15). 같은 행동의 중복 기여는 action_id UNIQUE가 막는다.
 * action_id는 아직 FK가 없다(단위 4의 마이그레이션이 actions 테이블과 함께 추가한다).
 */
@Entity
@Table(name = "weekly_goal_numeric_records")
public class NumericRecord {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "weekly_goal_id", nullable = false, updatable = false)
	private Long weeklyGoalId;

	@Column(name = "action_id", updatable = false)
	private Long actionId;

	@Enumerated(EnumType.STRING)
	@Column(name = "record_source", nullable = false, updatable = false, length = 20)
	private RecordSource source;

	@Column(nullable = false, updatable = false, precision = 10, scale = 2)
	private BigDecimal amount;

	@Column(updatable = false, length = 255)
	private String memo;

	@Generated(event = EventType.INSERT)
	@Column(name = "created_at", insertable = false, updatable = false)
	private LocalDateTime createdAt;

	protected NumericRecord() {
	}

	/** 직접 입력 기록. 행동 완료 기록은 단위 4가 같은 생성자 모양의 팩토리를 더한다. */
	public static NumericRecord directInput(Long weeklyGoalId, BigDecimal amount, String memo) {
		NumericRecord record = new NumericRecord();
		record.weeklyGoalId = weeklyGoalId;
		record.source = RecordSource.DIRECT_INPUT;
		record.amount = amount;
		record.memo = memo;
		return record;
	}

	public Long getId() {
		return id;
	}

	public Long getWeeklyGoalId() {
		return weeklyGoalId;
	}

	public Long getActionId() {
		return actionId;
	}

	public RecordSource getSource() {
		return source;
	}

	public BigDecimal getAmount() {
		return amount;
	}

	public String getMemo() {
		return memo;
	}

	public LocalDateTime getCreatedAt() {
		return createdAt;
	}
}
