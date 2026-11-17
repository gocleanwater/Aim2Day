package com.aim2day.goal.actions;

import com.aim2day.goal.common.exception.ErrorCode;
import org.springframework.http.HttpStatus;

/** 행동 도메인 오류 코드(API 명세 §2.8, 접두사 ACTION). 이름이 응답의 code라 바꾸면 안 된다. */
public enum ActionErrorCode implements ErrorCode {

	ACTION_WEEKLY_GOAL_REQUIRED(HttpStatus.UNPROCESSABLE_CONTENT, "연결할 주간 목표가 없습니다."),
	ACTION_WEEKLY_GOAL_AMBIGUOUS(HttpStatus.UNPROCESSABLE_CONTENT, "연결할 수 있는 주간 목표가 여러 개입니다. 하나를 선택해 주세요."),
	ACTION_FUTURE_COMPLETION(HttpStatus.UNPROCESSABLE_CONTENT, "수행일이 오늘보다 뒤인 행동은 완료할 수 없습니다."),
	ACTION_DATE_OUT_OF_RANGE(HttpStatus.UNPROCESSABLE_CONTENT, "목표에 연결한 행동은 이번 주 안의 날짜까지만 지정할 수 있습니다.");

	private final HttpStatus status;
	private final String defaultMessage;

	ActionErrorCode(HttpStatus status, String defaultMessage) {
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
