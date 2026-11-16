package com.aim2day.goal.common.exception;

import org.springframework.http.HttpStatus;

/**
 * goal-service가 쓰는 오류 코드(API 명세 §2.8). 이름이 곧 응답의 code 값이다.
 * 프론트엔드는 message가 아니라 code로 분기하므로 이름을 바꾸면 안 된다.
 * user-service 전용 코드(AUTH_* 코드와 TOO_MANY_REQUESTS)는 이 서비스가 내지 않으므로 넣지 않았다.
 * 409/422는 "상태 충돌 / 업무 규칙 위반"으로 구분한다(§2.8).
 */
public enum ErrorCode {

	MALFORMED_REQUEST(HttpStatus.BAD_REQUEST, "요청 형식이 올바르지 않습니다."),
	VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "입력값이 올바르지 않습니다."),
	UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다."),
	CSRF_TOKEN_INVALID(HttpStatus.FORBIDDEN, "보안 토큰이 올바르지 않습니다. 잠시 후 다시 시도해 주세요."),
	RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "요청한 대상을 찾을 수 없습니다."),

	VERSION_CONFLICT(HttpStatus.CONFLICT, "다른 곳에서 먼저 수정되었습니다. 최신 내용을 확인한 뒤 다시 시도해 주세요."),
	GOAL_LIMIT_EXCEEDED(HttpStatus.CONFLICT, "연간 목표는 최대 3개까지 만들 수 있습니다. 기존 목표를 삭제한 뒤 다시 시도해 주세요."),
	GOAL_DELETE_CONFIRM_REQUIRED(HttpStatus.CONFLICT, "하위 항목이 있는 목표입니다. 삭제를 확인해 주세요."),
	GOAL_PARENT_DELETED(HttpStatus.CONFLICT, "상위 목표가 삭제된 상태라 복원할 수 없습니다."),
	GOAL_RESTORE_EXPIRED(HttpStatus.CONFLICT, "삭제 후 30일이 지나 복원할 수 없습니다."),
	CRITERIA_ALREADY_CONFIRMED(HttpStatus.CONFLICT, "이미 확정된 달성 기준입니다."),

	GOAL_PARENT_UNAVAILABLE(HttpStatus.UNPROCESSABLE_CONTENT, "상위 목표를 사용할 수 없습니다."),
	GOAL_PARENT_PERIOD_MISMATCH(HttpStatus.UNPROCESSABLE_CONTENT, "기간이 상위 목표의 기간과 겹치지 않습니다."),
	ACTION_WEEKLY_GOAL_REQUIRED(HttpStatus.UNPROCESSABLE_CONTENT, "수행일을 포함하는 주간 목표가 없습니다."),
	ACTION_WEEKLY_GOAL_AMBIGUOUS(HttpStatus.UNPROCESSABLE_CONTENT, "연결할 주간 목표를 선택해 주세요."),
	CRITERIA_WEIGHT_SUM_INVALID(HttpStatus.UNPROCESSABLE_CONTENT, "달성 기준의 배점 합계는 100이어야 합니다."),
	CRITERIA_NOT_CONFIRMED(HttpStatus.UNPROCESSABLE_CONTENT, "확정되지 않은 달성 기준입니다."),
	ACTION_FUTURE_COMPLETION(HttpStatus.UNPROCESSABLE_CONTENT, "수행일이 미래인 행동은 완료할 수 없습니다."),
	ACTION_DATE_OUT_OF_RANGE(HttpStatus.UNPROCESSABLE_CONTENT, "목표와 연결된 행동의 수행일은 이번 주를 넘길 수 없습니다."),
	QUADRANT_OUT_OF_RANGE(HttpStatus.UNPROCESSABLE_CONTENT, "목표와 연결된 행동의 중요도는 50 이상이어야 합니다."),
	QUADRANT_DATE_INVALID(HttpStatus.UNPROCESSABLE_CONTENT, "오늘 행동만 분류할 수 있습니다."),
	GOAL_NOT_NUMERIC(HttpStatus.UNPROCESSABLE_CONTENT, "수치형 주간 목표에만 수치 기록을 남길 수 있습니다."),
	GOAL_MEASUREMENT_TYPE_IMMUTABLE(HttpStatus.UNPROCESSABLE_CONTENT, "측정 방식은 생성 후 바꿀 수 없습니다."),
	RECORD_NOT_DELETABLE(HttpStatus.UNPROCESSABLE_CONTENT, "행동 완료로 생긴 기록은 직접 삭제할 수 없습니다."),
	GOAL_ORDER_MISMATCH(HttpStatus.UNPROCESSABLE_CONTENT, "순서 목록이 현재 목표와 일치하지 않습니다."),
	CALENDAR_ITEM_TYPE_IMMUTABLE(HttpStatus.UNPROCESSABLE_CONTENT, "달력 항목의 종류는 바꿀 수 없습니다."),
	REPORT_INVALID_PERIOD(HttpStatus.UNPROCESSABLE_CONTENT, "조회할 수 없는 기간입니다."),

	INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버에 문제가 발생했습니다. 잠시 후 다시 시도해 주세요.");

	private final HttpStatus status;
	private final String defaultMessage;

	ErrorCode(HttpStatus status, String defaultMessage) {
		this.status = status;
		this.defaultMessage = defaultMessage;
	}

	public HttpStatus status() {
		return status;
	}

	public String defaultMessage() {
		return defaultMessage;
	}
}
