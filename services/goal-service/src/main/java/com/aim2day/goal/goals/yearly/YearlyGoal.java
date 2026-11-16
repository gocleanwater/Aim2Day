package com.aim2day.goal.goals.yearly;

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
import java.time.LocalDateTime;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

/**
 * 연간 목표(ERD 3.2-4). 사용자가 직접 고치는 필드는 title, description, color, status뿐이고 엔티티 저장으로 바꾼다.
 * 나머지는 서버가 계산하거나 다른 API가 바꾸는 값이라 updatable=false로 읽기 전용 매핑하고
 * YearlyGoalRepository의 직접 UPDATE로만 바꾼다(ERD 결정 17과 같은 이유).
 * 제목을 고치는 요청이 오래된 display_order나 기준 확정 값을 다시 써서 다른 요청의 변경을 지우는 일을 막는다.
 */
@Entity
@Table(name = "yearly_goals")
public class YearlyGoal {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "user_id", nullable = false, updatable = false)
	private Long userId;

	@Column(name = "target_year", nullable = false, updatable = false)
	private int targetYear;

	@Column(nullable = false, length = 100)
	private String title;

	@Column(columnDefinition = "TEXT")
	private String description;

	@Column(nullable = false, length = 20)
	private String color;

	/** 순서 저장은 version을 올리지 않으므로 직접 UPDATE로만 바꾼다. */
	@Column(name = "display_order", nullable = false, updatable = false)
	private int displayOrder;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private GoalStatus status;

	@Column(name = "is_criteria_confirmed", nullable = false, updatable = false)
	private boolean criteriaConfirmed;

	@Column(name = "initial_baseline_rate", updatable = false)
	private Integer initialBaselineRate;

	@Column(name = "criteria_confirmed_at", updatable = false)
	private LocalDateTime criteriaConfirmedAt;

	@Column(name = "criteria_last_revised_at", updatable = false)
	private LocalDateTime criteriaLastRevisedAt;

	@Column(name = "deleted_at", updatable = false)
	private LocalDateTime deletedAt;

	/** DB 기본값(CURRENT_TIMESTAMP)이 채운 UTC 시각. INSERT 직후 다시 읽어 응답에 쓴다. */
	@Generated(event = EventType.INSERT)
	@Column(name = "created_at", insertable = false, updatable = false)
	private LocalDateTime createdAt;

	/** 낙관적 락. 오래된 화면에서 온 수정이 최신 값을 덮어쓰지 못하게 한다. */
	@Version
	private Long version;

	/**
	 * INSERT 때만 Generated로 읽는다. UPDATE에도 Generated를 걸면 version 충돌로 0행이 갱신된 경우
	 * (H2의 UPDATE ... RETURNING) 충돌 예외 대신 "생성값 없음" 오류가 먼저 나 409로 바꿀 수 없다.
	 * UPDATE 뒤 최신 값은 서비스가 flush 후 refresh로 다시 읽는다.
	 */
	@Generated(event = EventType.INSERT)
	@Column(name = "updated_at", insertable = false, updatable = false)
	private LocalDateTime updatedAt;

	protected YearlyGoal() {
	}

	public YearlyGoal(Long userId, int targetYear, String title, String description, String color, int displayOrder) {
		this.userId = userId;
		this.targetYear = targetYear;
		this.title = title;
		this.description = description;
		this.color = color;
		this.displayOrder = displayOrder;
		this.status = GoalStatus.PROGRESS;
	}

	public Long getId() {
		return id;
	}

	public Long getUserId() {
		return userId;
	}

	public int getTargetYear() {
		return targetYear;
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

	public int getDisplayOrder() {
		return displayOrder;
	}

	public GoalStatus getStatus() {
		return status;
	}

	public boolean isCriteriaConfirmed() {
		return criteriaConfirmed;
	}

	public Integer getInitialBaselineRate() {
		return initialBaselineRate;
	}

	public LocalDateTime getCriteriaConfirmedAt() {
		return criteriaConfirmedAt;
	}

	public LocalDateTime getCriteriaLastRevisedAt() {
		return criteriaLastRevisedAt;
	}

	public LocalDateTime getCreatedAt() {
		return createdAt;
	}

	public Long getVersion() {
		return version;
	}

	public LocalDateTime getUpdatedAt() {
		return updatedAt;
	}

	/** 수정 가능한 네 필드만 바꾼다. 값이 모두 같으면 Hibernate가 UPDATE를 생략해 version도 오르지 않는다. */
	public void change(String title, String description, String color, GoalStatus status) {
		this.title = title;
		this.description = description;
		this.color = color;
		this.status = status;
	}
}
