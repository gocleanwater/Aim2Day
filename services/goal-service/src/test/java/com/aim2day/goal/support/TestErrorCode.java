package com.aim2day.goal.support;

import com.aim2day.goal.common.exception.ErrorCode;
import org.springframework.http.HttpStatus;

/** 도메인 enum이 ErrorCode를 구현하면 그대로 응답되는지 확인하기 위한 테스트 전용 코드. */
public enum TestErrorCode implements ErrorCode {

	SAMPLE_CONFLICT(HttpStatus.CONFLICT, "상태 충돌 예시"),
	SAMPLE_RULE(HttpStatus.UNPROCESSABLE_CONTENT, "업무 규칙 위반 예시");

	private final HttpStatus status;
	private final String defaultMessage;

	TestErrorCode(HttpStatus status, String defaultMessage) {
		this.status = status;
		this.defaultMessage = defaultMessage;
	}

	@Override
	public HttpStatus status() {
		return status;
	}

	@Override
	public String defaultMessage() {
		return defaultMessage;
	}
}
