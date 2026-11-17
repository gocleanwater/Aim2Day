package com.aim2day.goal.goals.weekly.record;

import com.aim2day.goal.common.exception.ErrorCode;
import org.springframework.http.HttpStatus;

/** 수치 기록 도메인 오류 코드(API 명세 §2.8, 접두사 RECORD). 이름이 응답의 code라 바꾸면 안 된다. */
public enum RecordErrorCode implements ErrorCode {

	RECORD_NOT_DELETABLE(HttpStatus.UNPROCESSABLE_CONTENT, "행동 완료로 생긴 기록은 직접 삭제할 수 없습니다.");

	private final HttpStatus status;
	private final String defaultMessage;

	RecordErrorCode(HttpStatus status, String defaultMessage) {
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
