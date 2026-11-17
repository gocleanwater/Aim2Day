package com.aim2day.goal.quadrant;

import com.aim2day.goal.common.exception.ErrorCode;
import org.springframework.http.HttpStatus;

/** 사분면 분류 오류 코드(API 명세 §2.8, 접두사 QUADRANT). 이름이 응답의 code라 바꾸면 안 된다. */
public enum QuadrantErrorCode implements ErrorCode {

	QUADRANT_OUT_OF_RANGE(HttpStatus.UNPROCESSABLE_CONTENT, "목표에 연결한 행동은 중요도 50 이상 영역에만 둘 수 있습니다."),
	QUADRANT_DATE_INVALID(HttpStatus.UNPROCESSABLE_CONTENT, "오늘 수행일인 행동만 분류할 수 있습니다.");

	private final HttpStatus status;
	private final String defaultMessage;

	QuadrantErrorCode(HttpStatus status, String defaultMessage) {
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
