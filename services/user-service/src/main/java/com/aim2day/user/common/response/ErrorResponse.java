package com.aim2day.user.common.response;

/**
 * 오류 응답 본문 {code, message, details} (API 명세 §2.1).
 * details가 없으면 null이어야 하므로 null도 필드로 직렬화한다(Jackson 기본 동작, NON_NULL로 바꾸지 말 것).
 */
public record ErrorResponse(String code, String message, Object details) {

	/** details 없이 오류 코드의 이름·기본 문구로 응답 본문을 만든다. */
	public static ErrorResponse of(ErrorCode errorCode) {
		return new ErrorResponse(errorCode.name(), errorCode.message(), null);
	}

	/** 부가 정보(검증 오류 목록, 남은 시도 횟수 등)를 details에 담아 응답 본문을 만든다. */
	public static ErrorResponse of(ErrorCode errorCode, Object details) {
		return new ErrorResponse(errorCode.name(), errorCode.message(), details);
	}
}
