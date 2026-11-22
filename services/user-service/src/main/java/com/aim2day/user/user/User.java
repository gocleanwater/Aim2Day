package com.aim2day.user.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/** users 테이블(ERD 3.1). 스키마는 Flyway V1이 만들고 Hibernate는 validate만 한다. */
@Entity
@Table(name = "users")
public class User {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	// UNIQUE(idx_users_email)와 utf8mb4_bin 정렬 규칙은 Flyway V1이 정의한다(Hibernate는 validate만 한다).
	@Column(nullable = false, length = 255)
	private String email;

	@Column(nullable = false, length = 50)
	private String username;

	/** BCrypt 해시. 로그·응답에 절대 내보내지 않는다(NFR-010). toString을 만들지 않는 이유이기도 하다. */
	@Column(name = "password_hash", nullable = false, length = 255)
	private String passwordHash;

	// 아래 두 값은 DB 기본값(CURRENT_TIMESTAMP, ON UPDATE)이 채운다. 앱이 쓰면 JVM 시계와 DB 시계가 섞인다.
	@Column(name = "created_at", insertable = false, updatable = false)
	private LocalDateTime createdAt;

	@Column(name = "updated_at", insertable = false, updatable = false)
	private LocalDateTime updatedAt;

	/** JPA가 엔티티를 만들 때 쓰는 기본 생성자. 직접 호출하지 않는다. */
	protected User() {
	}

	/** 신규 가입 계정을 만든다. email은 정규화된 값, passwordHash는 BCrypt 해시여야 한다. id·일시는 DB가 채운다. */
	public User(String email, String username, String passwordHash) {
		this.email = email;
		this.username = username;
		this.passwordHash = passwordHash;
	}

	public Long getId() {
		return id;
	}

	public String getEmail() {
		return email;
	}

	public String getUsername() {
		return username;
	}

	public String getPasswordHash() {
		return passwordHash;
	}

	/** 비밀번호 재설정. 이미 BCrypt로 해시한 값만 받는다. */
	public void changePasswordHash(String newPasswordHash) {
		this.passwordHash = newPasswordHash;
	}

	/** DB가 채운 UTC 시각(jdbc time_zone=UTC로 읽는다). */
	public LocalDateTime getCreatedAt() {
		return createdAt;
	}
}
