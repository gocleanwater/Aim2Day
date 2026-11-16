package com.aim2day.goal.common.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class VersionGuardTests {

	@Test
	void sameVersionPasses() {
		assertThatCode(() -> VersionGuard.check(3L, 3)).doesNotThrowAnyException();
	}

	@Test
	void staleVersionThrowsConflictWithCurrentVersion() {
		assertThatThrownBy(() -> VersionGuard.check(2L, 3))
				.isInstanceOfSatisfying(BusinessException.class, e -> {
					assertThat(e.getErrorCode()).isEqualTo(CommonErrorCode.VERSION_CONFLICT);
					assertThat(e.getDetails()).isEqualTo(Map.of("currentVersion", 3L));
				});
	}

	@Test
	void missingVersionIsValidationFailed() {
		assertThatThrownBy(() -> VersionGuard.check(null, 3))
				.isInstanceOfSatisfying(BusinessException.class, e -> {
					assertThat(e.getErrorCode()).isEqualTo(CommonErrorCode.VALIDATION_FAILED);
					assertThat(e.getDetails())
							.isEqualTo(Map.of("fieldErrors", List.of(new FieldErrorDetail("version", "REQUIRED"))));
				});
	}

	@Test
	void translateConflictFillsCurrentVersion() {
		assertThatThrownBy(() -> VersionGuard.translateConflict(() -> {
			throw new org.springframework.dao.OptimisticLockingFailureException("충돌");
		}, () -> 5L)).isInstanceOfSatisfying(BusinessException.class, e -> {
			assertThat(e.getErrorCode()).isEqualTo(CommonErrorCode.VERSION_CONFLICT);
			assertThat(e.getDetails()).isEqualTo(Map.of("currentVersion", 5L));
		});
	}

	@Test
	void translateConflictReturnsResultWhenNoConflict() {
		assertThat(VersionGuard.translateConflict(() -> "ok", () -> 5L)).isEqualTo("ok");
	}
}
