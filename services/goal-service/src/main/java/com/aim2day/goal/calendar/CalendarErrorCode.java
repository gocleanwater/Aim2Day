package com.aim2day.goal.calendar;

import com.aim2day.goal.common.exception.ErrorCode;
import org.springframework.http.HttpStatus;

/** 달력 도메인 오류 코드(API 명세 §2.8, 접두사 CALENDAR). 이름이 응답의 code라 바꾸면 안 된다. */
public enum CalendarErrorCode implements ErrorCode {

	CALENDAR_ITEM_TYPE_IMMUTABLE(HttpStatus.UNPROCESSABLE_CONTENT, "달력 항목의 종류는 바꿀 수 없습니다.");

	private final HttpStatus status;
	private final String defaultMessage;

	CalendarErrorCode(HttpStatus status, String defaultMessage) {
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
