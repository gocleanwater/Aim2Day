package com.aim2day.goal.calendar;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

/**
 * 달력 항목(ERD 3.4-10).
 *
 * is_completed, completed_at은 updatable=false다. 완료는 항목 행 잠금(FOR UPDATE)을 쥔 채 CalendarItemRepository의 직접
 * UPDATE로만 바꾼다. 제목만 고치는 오래된 수정 화면이 엔티티 저장으로 다른 인스턴스가 방금 한 완료를 되돌리지 못하게 하고,
 * 완료 체크가 version을 올리지 않아 열려 있는 수정 화면이 409가 되지 않게 하기 위해서다(행동과 같은 규칙).
 * item_type과 user_id도 생성 뒤에는 바뀌지 않는다.
 */
@Entity
@Table(name = "calendar_items")
public class CalendarItem {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "user_id", nullable = false, updatable = false)
	private Long userId;

	@Column(name = "yearly_goal_id")
	private Long yearlyGoalId;

	@Enumerated(EnumType.STRING)
	@Column(name = "item_type", nullable = false, updatable = false, length = 10)
	private CalendarItemType itemType;

	@Column(nullable = false, length = 255)
	private String title;

	@Column(columnDefinition = "TEXT")
	private String description;

	@Column(name = "start_date", nullable = false)
	private LocalDate startDate;

	@Column(name = "end_date", nullable = false)
	private LocalDate endDate;

	@Column(name = "is_completed", nullable = false, updatable = false)
	private boolean completed;

	@Column(name = "completed_at", updatable = false)
	private LocalDateTime completedAt;

	@Generated(event = EventType.INSERT)
	@Column(name = "created_at", insertable = false, updatable = false)
	private LocalDateTime createdAt;

	@Version
	private Long version;

	/** INSERT에만 Generated를 건다(UPDATE에 걸면 version 충돌이 409 대신 생성값 오류로 먼저 터진다, YearlyGoal과 같다). */
	@Generated(event = EventType.INSERT)
	@Column(name = "updated_at", insertable = false, updatable = false)
	private LocalDateTime updatedAt;

	protected CalendarItem() {
	}

	public CalendarItem(Long userId, Long yearlyGoalId, CalendarItemType itemType, String title, String description,
			LocalDate startDate, LocalDate endDate) {
		this.userId = userId;
		this.yearlyGoalId = yearlyGoalId;
		this.itemType = itemType;
		this.title = title;
		this.description = description;
		this.startDate = startDate;
		this.endDate = endDate;
	}

	public Long getId() {
		return id;
	}

	public Long getUserId() {
		return userId;
	}

	public Long getYearlyGoalId() {
		return yearlyGoalId;
	}

	public CalendarItemType getItemType() {
		return itemType;
	}

	public String getTitle() {
		return title;
	}

	public String getDescription() {
		return description;
	}

	public LocalDate getStartDate() {
		return startDate;
	}

	public LocalDate getEndDate() {
		return endDate;
	}

	public boolean isCompleted() {
		return completed;
	}

	public LocalDateTime getCompletedAt() {
		return completedAt;
	}

	public Long getVersion() {
		return version;
	}

	public void change(Long yearlyGoalId, String title, String description, LocalDate startDate, LocalDate endDate) {
		this.yearlyGoalId = yearlyGoalId;
		this.title = title;
		this.description = description;
		this.startDate = startDate;
		this.endDate = endDate;
	}
}
