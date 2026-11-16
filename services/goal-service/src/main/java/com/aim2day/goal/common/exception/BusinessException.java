package com.aim2day.goal.common.exception;

/**
 * 업무 규칙 위반을 알리는 예외. 서비스 코드가 던지면 GlobalExceptionHandler가
 * ErrorCode의 HTTP 상태와 {code, message, details} 본문으로 바꿔 응답한다.
 * details는 코드별로 명세 §2.8이 정한 모양의 객체(Map 등)를 넣는다.
 */
public class BusinessException extends RuntimeException {

	private final ErrorCode errorCode;
	private final transient Object details;

	public BusinessException(ErrorCode errorCode) {
		this(errorCode, null, null);
	}

	public BusinessException(ErrorCode errorCode, Object details) {
		this(errorCode, null, details);
	}

	/** message를 직접 줄 때(예: 연도를 문장에 넣는 GOAL_LIMIT_EXCEEDED). null이면 기본 문구. */
	public BusinessException(ErrorCode errorCode, String message, Object details) {
		super(message != null ? message : errorCode.defaultMessage());
		this.errorCode = errorCode;
		this.details = details;
	}

	public ErrorCode getErrorCode() {
		return errorCode;
	}

	public Object getDetails() {
		return details;
	}
}
