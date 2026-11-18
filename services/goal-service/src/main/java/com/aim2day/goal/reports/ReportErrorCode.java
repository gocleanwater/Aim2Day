package com.aim2day.goal.reports;

import com.aim2day.goal.common.exception.ErrorCode;
import org.springframework.http.HttpStatus;

/**
 * 리포트 도메인 오류 코드(API 명세 §2.8, 접두사 REPORT). 달력 기간 조회(단위 7)가 먼저 쓰고 리포트(단위 9)가 재사용한다.
 * 이름이 응답의 code라 바꾸면 안 된다.
 */
public enum ReportErrorCode implements ErrorCode {

	REPORT_INVALID_PERIOD(HttpStatus.UNPROCESSABLE_CONTENT, "조회할 수 없는 기간입니다.");

	private final HttpStatus status;
	private final String defaultMessage;

	ReportErrorCode(HttpStatus status, String defaultMessage) {
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
