package com.aim2day.goal.common.exception;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 서비스 계층 입력 검증 결과를 모아 한 번에 400 VALIDATION_FAILED로 던지는 도우미.
 * 앞뒤 공백 제거 후 길이 검사처럼 Bean Validation 어노테이션으로 표현하기 어려운 규칙에 쓴다.
 */
public final class FieldErrors {

	private final List<FieldErrorDetail> errors = new ArrayList<>();

	public FieldErrors add(String field, ValidationReason reason) {
		errors.add(new FieldErrorDetail(field, reason.name()));
		return this;
	}

	public boolean isEmpty() {
		return errors.isEmpty();
	}

	public void throwIfAny() {
		if (!errors.isEmpty()) {
			throw toException();
		}
	}

	/** 오류 하나를 즉시 던져야 하는 곳에서 `throw new FieldErrors().add(...).toException()` 형태로 쓴다. */
	public BusinessException toException() {
		return new BusinessException(CommonErrorCode.VALIDATION_FAILED, Map.of("fieldErrors", List.copyOf(errors)));
	}
}
