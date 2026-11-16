package com.aim2day.user.common.response;

/**
 * 오류 응답 본문 {code, message, details} (API 명세 §2.1).
 * details가 없으면 null이어야 하므로 null도 필드로 직렬화한다(Jackson 기본 동작, NON_NULL로 바꾸지 말 것).
 */
public record ErrorResponse(String code, String message, Object details) {

	public static ErrorResponse of(ErrorCode errorCode) {
		return new ErrorResponse(errorCode.name(), errorCode.message(), null);
	}

	public static ErrorResponse of(ErrorCode errorCode, Object details) {
		return new ErrorResponse(errorCode.name(), errorCode.message(), details);
	}
}
