package com.aim2day.goal.onboarding;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.LocalDateTime;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.generator.EventType;
import org.hibernate.type.SqlTypes;

/**
 * 온보딩 임시 저장(ERD 3.1-3). 사용자당 1행.
 *
 * draft_data는 MySQL JSON 열이다. 서버는 내용을 해석하지 않으므로 엔티티는 JSON 텍스트를 String으로 들고,
 * {@code @JdbcTypeCode(SqlTypes.JSON)}로 "이 문자열은 이미 JSON 문서"임을 Hibernate에 알린다. 그래야 H2·MySQL 모두
 * 객체는 객체로 저장되고(문자열 한 겹 더 감싸지 않고) 같은 텍스트로 돌아온다.
 */
@Entity
@Table(name = "onboarding_drafts")
public class OnboardingDraft {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "user_id", nullable = false, updatable = false)
	private Long userId;

	@Column(name = "current_step", nullable = false)
	private int currentStep;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "draft_data", nullable = false, columnDefinition = "JSON")
	private String draftData;

	/** 낙관적 락. 오래된 탭이 최신 임시 저장을 덮어쓰지 못하게 한다. */
	@Version
	private Long version;

	/** INSERT 때만 DB 기본값으로 읽는다. UPDATE 뒤 최신 값은 서비스가 flush 후 refresh로 다시 읽는다. */
	@Generated(event = EventType.INSERT)
	@Column(name = "updated_at", insertable = false, updatable = false)
	private LocalDateTime updatedAt;

	protected OnboardingDraft() {
	}

	public OnboardingDraft(Long userId, int currentStep, String draftData) {
		this.userId = userId;
		this.currentStep = currentStep;
		this.draftData = draftData;
	}

	public Long getId() {
		return id;
	}

	public Long getUserId() {
		return userId;
	}

	public int getCurrentStep() {
		return currentStep;
	}

	public String getDraftData() {
		return draftData;
	}

	public Long getVersion() {
		return version;
	}

	public LocalDateTime getUpdatedAt() {
		return updatedAt;
	}

	/** 임시 저장본의 단계와 입력 내용을 덮어쓴다. version은 @Version이 저장 시점에 올린다. */
	public void change(int currentStep, String draftData) {
		this.currentStep = currentStep;
		this.draftData = draftData;
	}
}
