package com.aim2day.user.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/** 이메일 UNIQUE 위반 판정이 정확한 제약 이름·실제 중복 키 오류로만 참이 되는지 검증한다. */
class SignupConstraintDetectionTests {

	private static DataIntegrityViolationException wrap(Throwable cause) {
		return new DataIntegrityViolationException("could not execute statement", cause);
	}

	private static ConstraintViolationException cve(String constraint) {
		return new ConstraintViolationException("dup", new SQLException("x", "23000", 1062), constraint);
	}

	private static SQLException sql(String message, String state, int code) {
		return new SQLException(message, state, code);
	}

	@Test
	void hibernateConstraintNameMatchesIgnoringCaseAndSchemaPrefix() {
		assertThat(SignupService.isEmailUniqueViolation(wrap(cve("idx_users_email")))).isTrue();
		assertThat(SignupService.isEmailUniqueViolation(wrap(cve("users.idx_users_email")))).isTrue();
		assertThat(SignupService.isEmailUniqueViolation(wrap(cve("PUBLIC.IDX_USERS_EMAIL")))).isTrue();
		assertThat(SignupService.isEmailUniqueViolation(wrap(cve("`users`.`idx_users_email`")))).isTrue();
	}

	@Test
	void similarConstraintNamesAreNotEmailViolations() {
		assertThat(SignupService.isEmailUniqueViolation(wrap(cve("idx_users_email_backup")))).isFalse();
		assertThat(SignupService.isEmailUniqueViolation(wrap(cve("my_idx_users_email")))).isFalse();
		assertThat(SignupService.isEmailUniqueViolation(wrap(cve("idx_users_username")))).isFalse();
	}

	@Test
	void messageFallbackRequiresRealDuplicateKeyErrorAndExactKey() {
		assertThat(SignupService.isEmailUniqueViolation(wrap(
				sql("Duplicate entry 'a@b.c' for key 'users.idx_users_email'", "23000", 1062)))).isTrue();
		assertThat(SignupService.isEmailUniqueViolation(wrap(
				sql("Duplicate entry 'a@b.c' for key 'idx_users_email'", "23000", 1062)))).isTrue();
		assertThat(SignupService.isEmailUniqueViolation(wrap(sql(
				"Unique index or primary key violation: \"PUBLIC.IDX_USERS_EMAIL_INDEX_4 ON PUBLIC.USERS(EMAIL NULLS FIRST)\"",
				"23505", 23505)))).isTrue();

		assertThat(SignupService.isEmailUniqueViolation(wrap(sql(
				"Unique index or primary key violation: \"PUBLIC.IDX_USERS_EMAIL INDEX PUBLIC.IDX_USERS_EMAIL_INDEX_4 ON PUBLIC.USERS(EMAIL NULLS FIRST) VALUES ( /* 6 */ 'a@b.c' )\"",
				"23505", 23505)))).isTrue();

		// 같은 문구라도 중복 키 오류가 아니면(NOT NULL 위반, 다른 오류 코드) 아니다.
		assertThat(SignupService.isEmailUniqueViolation(wrap(
				sql("Duplicate entry for key 'users.idx_users_email'", "23502", 1048)))).isFalse();
		assertThat(SignupService.isEmailUniqueViolation(wrap(
				sql("Duplicate entry for key 'users.idx_users_email'", "HY000", 1062)))).isFalse();
		// 중복 키 오류여도 다른 키 이름이면 아니다.
		assertThat(SignupService.isEmailUniqueViolation(wrap(
				sql("Duplicate entry 'x' for key 'users.idx_users_email_backup'", "23000", 1062)))).isFalse();
		assertThat(SignupService.isEmailUniqueViolation(wrap(
				sql("Duplicate entry 'idx_users_email' for key 'users.PRIMARY'", "23000", 1062)))).isFalse();
		// 예외 메시지에만 이름이 들어간 일반 무결성 오류는 아니다.
		assertThat(SignupService.isEmailUniqueViolation(
				new DataIntegrityViolationException("NULL not allowed; idx_users_email mentioned"))).isFalse();
	}
}
