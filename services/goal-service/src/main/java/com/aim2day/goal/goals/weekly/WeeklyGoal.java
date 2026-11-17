package com.aim2day.goal.goals.weekly;

import com.aim2day.goal.goals.GoalStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

/**
 * 주간 목표(ERD 3.2-7). current_value와 progress_rate는 서버가 계산하는 값이라 updatable=false로 읽기 전용이다.
 * 이 두 컬럼은 ProgressEngine의 직접 UPDATE로만 바꾼다(ERD 결정 16·17). 엔티티 저장이 이 값을 다시 쓰면
 * 읽은 뒤 다른 인스턴스가 더한 기여량이 사라지고, 직접 UPDATE는 version을 올리지 않으므로 낙관적 락도 이를 잡지 못한다.
 * 사용자가 고치는 필드(title, description, color, status, target_value, weight, monthly_goal_id)만 updatable이다.
 */
@Entity
@Table(name = "weekly_goals")
public class WeeklyGoal {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "monthly_goal_id", nullable = false)
	private Long monthlyGoalId;

	@Column(name = "start_date", nullable = false, updatable = false)
	private LocalDate startDate;

	@Column(name = "end_date", nullable = false, updatable = false)
	private LocalDate endDate;

	@Column(nullable = false, length = 100)
	private String title;

	@Column(columnDefinition = "TEXT")
	private String description;

	@Column(length = 20)
	private String color;

	@Enumerated(EnumType.STRING)
	@Column(name = "measurement_type", nullable = false, updatable = false, length = 20)
	private MeasurementType measurementType;

	@Column(name = "target_value", precision = 10, scale = 2)
	private BigDecimal targetValue;

	@Column(name = "current_value", nullable = false, updatable = false, precision = 10, scale = 2)
	private BigDecimal currentValue = new BigDecimal("0.00");

	@Column(nullable = false, precision = 5, scale = 2)
	private BigDecimal weight;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private GoalStatus status;

	@Column(name = "progress_rate", nullable = false, updatable = false, precision = 5, scale = 2)
	private BigDecimal progressRate = new BigDecimal("0.00");

	@Column(name = "deleted_at", updatable = false)
	private LocalDateTime deletedAt;

	@Generated(event = EventType.INSERT)
	@Column(name = "created_at", insertable = false, updatable = false)
	private LocalDateTime createdAt;

	@Version
	private Long version;

	@Generated(event = EventType.INSERT)
	@Column(name = "updated_at", insertable = false, updatable = false)
	private LocalDateTime updatedAt;

	protected WeeklyGoal() {
	}

	public WeeklyGoal(Long monthlyGoalId, LocalDate startDate, LocalDate endDate, String title, String description,
			String color, MeasurementType measurementType, BigDecimal targetValue, BigDecimal weight) {
		this.monthlyGoalId = monthlyGoalId;
		this.startDate = startDate;
		this.endDate = endDate;
		this.title = title;
		this.description = description;
		this.color = color;
		this.measurementType = measurementType;
		this.targetValue = targetValue;
		this.weight = weight;
		this.status = GoalStatus.PROGRESS;
	}

	public Long getId() {
		return id;
	}

	public Long getMonthlyGoalId() {
		return monthlyGoalId;
	}

	public LocalDate getStartDate() {
		return startDate;
	}

	public LocalDate getEndDate() {
		return endDate;
	}

	public String getTitle() {
		return title;
	}

	public String getDescription() {
		return description;
	}

	public String getColor() {
		return color;
	}

	public MeasurementType getMeasurementType() {
		return measurementType;
	}

	public BigDecimal getTargetValue() {
		return targetValue;
	}

	public BigDecimal getCurrentValue() {
		return currentValue;
	}

	public BigDecimal getWeight() {
		return weight;
	}

	public GoalStatus getStatus() {
		return status;
	}

	public BigDecimal getProgressRate() {
		return progressRate;
	}

	public Long getVersion() {
		return version;
	}

	public void change(String title, String description, String color, GoalStatus status, BigDecimal targetValue,
			BigDecimal weight, Long monthlyGoalId) {
		this.title = title;
		this.description = description;
		this.color = color;
		this.status = status;
		this.targetValue = targetValue;
		this.weight = weight;
		this.monthlyGoalId = monthlyGoalId;
	}

	/** 소프트 삭제 시각(UTC). 삭제·복원은 직접 UPDATE로만 바꾸므로 setter는 없다. */
	public LocalDateTime getDeletedAt() {
		return deletedAt;
	}
}
