package com.aim2day.goal.goals;

import com.aim2day.goal.common.exception.ErrorCode;
import org.springframework.http.HttpStatus;

/** 목표 도메인 오류 코드(API 명세 §2.8). 이름이 응답의 code라 바꾸면 안 된다. 단위 3 이후 코드가 더해진다. */
public enum GoalErrorCode implements ErrorCode {

	GOAL_LIMIT_EXCEEDED(HttpStatus.CONFLICT, "연간 목표는 최대 3개까지 만들 수 있습니다. 기존 목표를 삭제한 뒤 다시 시도해 주세요."),
	GOAL_ORDER_MISMATCH(HttpStatus.UNPROCESSABLE_CONTENT, "순서 목록이 현재 목표와 일치하지 않습니다.");

	private final HttpStatus status;
	private final String defaultMessage;

	GoalErrorCode(HttpStatus status, String defaultMessage) {
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
