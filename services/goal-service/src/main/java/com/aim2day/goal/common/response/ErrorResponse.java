package com.aim2day.goal.common.response;

import com.aim2day.goal.common.exception.ErrorCode;

/**
 * 오류 응답 본문. 성공 응답의 data 자리에 details를 쓴다(API 명세 §2.1).
 * details가 없으면 필드를 생략하지 않고 null로 내보낸다(Jackson 기본 동작).
 */
public record ErrorResponse(String code, String message, Object details) {

	/** 기본 문구와 details 없이 오류 응답을 만든다. */
	public static ErrorResponse of(ErrorCode errorCode) {
		return new ErrorResponse(errorCode.name(), errorCode.defaultMessage(), null);
	}

	/** 기본 문구에 details만 붙인 오류 응답을 만든다. */
	public static ErrorResponse of(ErrorCode errorCode, Object details) {
		return new ErrorResponse(errorCode.name(), errorCode.defaultMessage(), details);
	}

	/** 문구까지 직접 지정한 오류 응답을 만든다(예: 연도가 들어가는 GOAL_LIMIT_EXCEEDED). */
	public static ErrorResponse of(ErrorCode errorCode, String message, Object details) {
		return new ErrorResponse(errorCode.name(), message, details);
	}
}
