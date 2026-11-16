package com.aim2day.user.common.response;

/**
 * 업무 규칙 위반을 나타내는 예외. 서비스 계층에서 던지면 GlobalExceptionHandler가
 * ErrorCode의 HTTP 상태와 {code, message, details} 본문으로 바꿔 준다.
 */
public class BusinessException extends RuntimeException {

	private final ErrorCode errorCode;
	private final transient Object details;

	public BusinessException(ErrorCode errorCode) {
		this(errorCode, null);
	}

	public BusinessException(ErrorCode errorCode, Object details) {
		super(errorCode.message());
		this.errorCode = errorCode;
		this.details = details;
	}

	public ErrorCode errorCode() {
		return errorCode;
	}

	public Object details() {
		return details;
	}
}
