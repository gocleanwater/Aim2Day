package com.aim2day.goal.common.exception;

/**
 * 업무 규칙 위반을 알리는 예외. 서비스 코드가 던지면 GlobalExceptionHandler가
 * ErrorCode의 HTTP 상태와 {code, message, details} 본문으로 바꿔 응답한다.
 * details는 코드별로 명세 §2.8이 정한 모양의 객체(Map 등)를 넣는다.
 */
public class BusinessException extends RuntimeException {

	private final ErrorCode errorCode;
	private final transient Object details;

	/** details 없이 기본 문구로 만드는 생성자. 응답 본문의 details는 null이다. */
	public BusinessException(ErrorCode errorCode) {
		this(errorCode, null, null);
	}

	/** 기본 문구에 오류 상세(details)만 붙이는 생성자. 예: 하위 목표 개수, 후보 주간 목표 목록. */
	public BusinessException(ErrorCode errorCode, Object details) {
		this(errorCode, null, details);
	}

	/** message를 직접 줄 때(예: 연도를 문장에 넣는 GOAL_LIMIT_EXCEEDED). null이면 기본 문구. */
	public BusinessException(ErrorCode errorCode, String message, Object details) {
		super(message != null ? message : errorCode.defaultMessage());
		this.errorCode = errorCode;
		this.details = details;
	}

	/** 응답의 HTTP 상태와 code를 정하는 오류 코드를 돌려준다. */
	public ErrorCode getErrorCode() {
		return errorCode;
	}

	/** 응답 본문 details에 담을 값을 돌려준다. 없으면 null이다. */
	public Object getDetails() {
		return details;
	}
}
