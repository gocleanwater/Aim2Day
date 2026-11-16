package com.aim2day.goal.common.exception;

import org.springframework.http.HttpStatus;

/**
 * 모든 도메인이 공유하는 오류 코드(API 명세 §2.8). 이름이 곧 응답의 code 값이다.
 * 프론트엔드는 message가 아니라 code로 분기하므로 이름을 바꾸면 안 된다.
 * 도메인 코드(GOAL_*, ACTION_* 등)는 각 도메인 패키지의 enum이 ErrorCode를 구현해 따로 가진다(결정 21).
 * user-service 전용 코드(AUTH_*, TOO_MANY_REQUESTS)는 이 서비스가 내지 않으므로 넣지 않았다.
 */
public enum CommonErrorCode implements ErrorCode {

	MALFORMED_REQUEST(HttpStatus.BAD_REQUEST, "요청 형식이 올바르지 않습니다."),
	VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "입력값이 올바르지 않습니다."),
	UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다."),
	CSRF_TOKEN_INVALID(HttpStatus.FORBIDDEN, "보안 토큰이 올바르지 않습니다. 잠시 후 다시 시도해 주세요."),
	RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "요청한 대상을 찾을 수 없습니다."),
	VERSION_CONFLICT(HttpStatus.CONFLICT, "다른 곳에서 먼저 수정되었습니다. 최신 내용을 확인한 뒤 다시 시도해 주세요."),
	INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버에 문제가 발생했습니다. 잠시 후 다시 시도해 주세요.");

	private final HttpStatus status;
	private final String defaultMessage;

	CommonErrorCode(HttpStatus status, String defaultMessage) {
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
