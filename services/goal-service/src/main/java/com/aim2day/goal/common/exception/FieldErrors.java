package com.aim2day.goal.common.exception;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 서비스 계층 입력 검증 결과를 모아 한 번에 400 VALIDATION_FAILED로 던지는 도우미.
 * 앞뒤 공백 제거 후 길이 검사처럼 Bean Validation 어노테이션으로 표현하기 어려운 규칙에 쓴다.
 */
public final class FieldErrors {

	private final List<FieldErrorDetail> errors;
	/** 필드 경로 앞에 붙일 접두사. 온보딩 일괄 저장에서 {@code yearlyGoals[0].title}처럼 쓴다. */
	private final String prefix;

	public FieldErrors() {
		this(new ArrayList<>(), "");
	}

	private FieldErrors(List<FieldErrorDetail> errors, String prefix) {
		this.errors = errors;
		this.prefix = prefix;
	}

	/**
	 * 같은 오류 목록에 쌓되 필드 이름 앞에 접두사를 붙이는 뷰. 개별 생성 API의 검증 메서드를 그대로 재사용하면서
	 * 일괄 요청 안의 위치(배열 인덱스)를 오류 경로에 남긴다. 뷰와 원본은 목록을 공유하므로 원본의 throwIfAny가 모두 본다.
	 */
	public FieldErrors scoped(String scopePrefix) {
		return new FieldErrors(errors, prefix + scopePrefix);
	}

	/** 필드 하나의 검증 실패를 목록에 추가한다(접두사가 있으면 경로 앞에 붙는다). 연쇄 호출을 위해 자기 자신을 돌려준다. */
	public FieldErrors add(String field, ValidationReason reason) {
		errors.add(new FieldErrorDetail(prefix + field, reason.name()));
		return this;
	}

	/** 지금까지 모인 검증 오류가 없으면 true다. */
	public boolean isEmpty() {
		return errors.isEmpty();
	}

	/** 모인 오류가 하나라도 있으면 400 VALIDATION_FAILED 예외를 던진다. 없으면 아무것도 하지 않는다. 여러 필드 오류를 한 번에 알리려고 검증이 끝난 뒤 한 번만 부른다. */
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
