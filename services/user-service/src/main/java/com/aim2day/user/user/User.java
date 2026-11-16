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

	protected User() {
	}

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
}
