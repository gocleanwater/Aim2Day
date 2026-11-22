package com.aim2day.goal.goals.monthly;

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
 * 월간 목표(ERD 3.2-6). 사용자가 고치는 필드는 title, description, color, status뿐이다.
 * progress_rate는 서버가 계산하므로 updatable=false로 읽기 전용 매핑하고 ProgressEngine의 직접 UPDATE로만 바꾼다
 * (ERD 결정 17). 제목을 고치는 요청이 오래된 진행률을 다시 써서 다른 요청의 계산을 지우는 일을 막는다.
 */
@Entity
@Table(name = "monthly_goals")
public class MonthlyGoal {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "yearly_goal_id", nullable = false, updatable = false)
	private Long yearlyGoalId;

	@Column(name = "start_date", nullable = false, updatable = false)
	private LocalDate startDate;

	@Column(name = "end_date", nullable = false, updatable = false)
	private LocalDate endDate;

	@Column(nullable = false, length = 100)
	private String title;

	@Column(columnDefinition = "TEXT")
	private String description;

	/** null이면 부모 연간 목표의 색을 쓴다(GOAL-011). */
	@Column(length = 20)
	private String color;

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

	protected MonthlyGoal() {
	}

	public MonthlyGoal(Long yearlyGoalId, LocalDate startDate, LocalDate endDate, String title, String description,
			String color) {
		this.yearlyGoalId = yearlyGoalId;
		this.startDate = startDate;
		this.endDate = endDate;
		this.title = title;
		this.description = description;
		this.color = color;
		this.status = GoalStatus.PROGRESS;
	}

	public Long getId() {
		return id;
	}

	public Long getYearlyGoalId() {
		return yearlyGoalId;
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

	public GoalStatus getStatus() {
		return status;
	}

	public BigDecimal getProgressRate() {
		return progressRate;
	}

	public Long getVersion() {
		return version;
	}

	/** 수정 가능한 필드(제목·설명·색·상태)만 바꾼다. color가 null이면 부모 연간 목표의 색을 따른다. 값이 모두 같으면 UPDATE가 생략되어 version도 오르지 않는다. */
	public void change(String title, String description, String color, GoalStatus status) {
		this.title = title;
		this.description = description;
		this.color = color;
		this.status = status;
	}

	/** 소프트 삭제 시각(UTC). 삭제·복원은 직접 UPDATE로만 바꾸므로 setter는 없다. */
	public LocalDateTime getDeletedAt() {
		return deletedAt;
	}
}
