package com.aim2day.goal.actions;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * 행동(ERD 3.3-8). weekly_goal_id가 null이면 MAINTENANCE다.
 *
 * is_completed, completed_at, deleted_at은 updatable=false다. 완료·삭제는 행 잠금(FOR UPDATE)을 쥔 채
 * ActionRepository의 직접 UPDATE로만 바꾼다. 엔티티 저장이 이 값을 다시 쓰면 제목만 고치는 오래된 화면 상태가
 * 다른 인스턴스가 방금 한 완료·삭제를 되돌린다(주간 목표의 current_value와 같은 문제, ERD 결정 17).
 * 직접 UPDATE는 version을 올리지 않으므로 완료 체크 때문에 열려 있는 수정 화면이 409가 되지도 않는다.
 * 사용자가 고치는 필드(title, performed_date, due_date, result_summary, weekly_goal_id, contribution_value)만 updatable이다.
 */
@Entity
@Table(name = "actions")
public class Action {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "user_id", nullable = false, updatable = false)
	private Long userId;

	@Column(name = "weekly_goal_id")
	private Long weeklyGoalId;

	@Column(nullable = false, length = 255)
	private String title;

	@Column(name = "performed_date", nullable = false)
	private LocalDate performedDate;

	@Column(name = "due_date")
	private LocalDate dueDate;

	@Column(name = "is_completed", nullable = false, updatable = false)
	private boolean completed;

	@Column(name = "completed_at", updatable = false)
	private LocalDateTime completedAt;

	@Column(name = "contribution_value", precision = 10, scale = 2)
	private BigDecimal contributionValue;

	@Column(name = "result_summary", columnDefinition = "TEXT")
	private String resultSummary;

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

	protected Action() {
	}

	public Action(Long userId, Long weeklyGoalId, String title, LocalDate performedDate, LocalDate dueDate,
			BigDecimal contributionValue) {
		this.userId = userId;
		this.weeklyGoalId = weeklyGoalId;
		this.title = title;
		this.performedDate = performedDate;
		this.dueDate = dueDate;
		this.contributionValue = contributionValue;
	}

	public Long getId() {
		return id;
	}

	public Long getUserId() {
		return userId;
	}

	public Long getWeeklyGoalId() {
		return weeklyGoalId;
	}

	public String getTitle() {
		return title;
	}

	public LocalDate getPerformedDate() {
		return performedDate;
	}

	public LocalDate getDueDate() {
		return dueDate;
	}

	public boolean isCompleted() {
		return completed;
	}

	public LocalDateTime getCompletedAt() {
		return completedAt;
	}

	public BigDecimal getContributionValue() {
		return contributionValue;
	}

	public String getResultSummary() {
		return resultSummary;
	}

	public Long getVersion() {
		return version;
	}

	public ActionType getType() {
		return weeklyGoalId == null ? ActionType.MAINTENANCE : ActionType.GOAL;
	}

	/**
	 * 사용자가 고칠 수 있는 필드를 한꺼번에 바꾼다. 검증과 version 비교는 서비스가 끝낸 뒤 부른다.
	 * 완료 여부와 삭제 시각은 진행률 계산과 얽혀 있어 전용 UPDATE 쿼리로만 바꾼다.
	 */
	public void change(String title, LocalDate performedDate, LocalDate dueDate, String resultSummary,
			Long weeklyGoalId, BigDecimal contributionValue) {
		this.title = title;
		this.performedDate = performedDate;
		this.dueDate = dueDate;
		this.resultSummary = resultSummary;
		this.weeklyGoalId = weeklyGoalId;
		this.contributionValue = contributionValue;
	}
}
