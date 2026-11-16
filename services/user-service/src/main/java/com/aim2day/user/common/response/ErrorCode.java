package com.aim2day.user.common.response;

import org.springframework.http.HttpStatus;

/**
 * user-service가 쓰는 오류 코드(API 명세 §2.8). 이름이 곧 응답의 code다.
 * 프론트엔드는 message가 아니라 code로 분기하므로 이름을 바꾸면 안 된다.
 * goal-service 전용 코드(GOAL_LIMIT_EXCEEDED 등)는 의도적으로 넣지 않았다.
 */
public enum ErrorCode {

	MALFORMED_REQUEST(HttpStatus.BAD_REQUEST, "요청 형식이 올바르지 않습니다."),
	VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "입력값이 올바르지 않습니다."),
	// 만료·이미 사용·변조를 구분하지 않는다(구분하면 토큰 상태를 추측할 수 있다).
	INVALID_TOKEN(HttpStatus.BAD_REQUEST, "유효하지 않거나 만료된 링크입니다."),
	UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다."),
	// 이메일 없음과 비밀번호 불일치를 같은 코드·문구로 응답해 계정 존재 여부를 숨긴다.
	INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호가 올바르지 않습니다."),
	EMAIL_NOT_VERIFIED(HttpStatus.FORBIDDEN, "이메일 인증이 완료되지 않았습니다."),
	CSRF_TOKEN_INVALID(HttpStatus.FORBIDDEN, "보안 토큰이 유효하지 않습니다. 잠시 후 다시 시도해 주세요."),
	RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "요청한 리소스를 찾을 수 없습니다."),
	EMAIL_ALREADY_EXISTS(HttpStatus.CONFLICT, "이미 가입된 이메일입니다."),
	TOO_MANY_REQUESTS(HttpStatus.TOO_MANY_REQUESTS, "요청이 너무 잦습니다. 잠시 후 다시 시도해 주세요."),
	INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 오류가 발생했습니다. 잠시 후 다시 시도해 주세요."),
	MAIL_SEND_FAILED(HttpStatus.BAD_GATEWAY, "메일을 보내지 못했습니다. 잠시 후 다시 시도해 주세요.");

	private final HttpStatus status;
	private final String message;

	ErrorCode(HttpStatus status, String message) {
		this.status = status;
		this.message = message;
	}

	public HttpStatus status() {
		return status;
	}

	public String message() {
		return message;
	}
}
