package com.aim2day.goal.goals;

import com.aim2day.goal.common.exception.ErrorCode;
import org.springframework.http.HttpStatus;

/** 목표 도메인 오류 코드(API 명세 §2.8). 이름이 응답의 code라 바꾸면 안 된다. */
public enum GoalErrorCode implements ErrorCode {

	GOAL_LIMIT_EXCEEDED(HttpStatus.CONFLICT, "연간 목표는 최대 3개까지 만들 수 있습니다. 기존 목표를 삭제한 뒤 다시 시도해 주세요."),
	GOAL_DELETE_CONFIRM_REQUIRED(HttpStatus.CONFLICT, "하위 목표와 미완료 행동이 함께 숨겨집니다. 삭제할까요?"),
	GOAL_PARENT_DELETED(HttpStatus.CONFLICT, "상위 목표가 삭제된 상태라 복원할 수 없습니다. 상위 목표를 먼저 복원해 주세요."),
	GOAL_RESTORE_EXPIRED(HttpStatus.CONFLICT, "복구 기한(삭제 후 30일)이 지나 복원할 수 없습니다."),
	GOAL_ORDER_MISMATCH(HttpStatus.UNPROCESSABLE_CONTENT, "순서 목록이 현재 목표와 일치하지 않습니다."),
	GOAL_PARENT_UNAVAILABLE(HttpStatus.UNPROCESSABLE_CONTENT, "상위 목표를 사용할 수 없습니다."),
	GOAL_PARENT_PERIOD_MISMATCH(HttpStatus.UNPROCESSABLE_CONTENT, "기간이 상위 목표의 기간과 겹치지 않습니다."),
	GOAL_NOT_NUMERIC(HttpStatus.UNPROCESSABLE_CONTENT, "수치형 주간 목표에만 수치 기록을 남길 수 있습니다."),
	GOAL_MEASUREMENT_TYPE_IMMUTABLE(HttpStatus.UNPROCESSABLE_CONTENT, "측정 방식은 생성 후 바꿀 수 없습니다.");

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
