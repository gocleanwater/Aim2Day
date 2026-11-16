package com.aim2day.goal.goals.criteria;

import com.aim2day.goal.common.exception.ErrorCode;
import org.springframework.http.HttpStatus;

/** 달성 기준 오류 코드(API 명세 §2.8). */
public enum CriteriaErrorCode implements ErrorCode {

	CRITERIA_ALREADY_CONFIRMED(HttpStatus.CONFLICT, "이미 확정된 달성 기준입니다."),
	CRITERIA_WEIGHT_SUM_INVALID(HttpStatus.UNPROCESSABLE_CONTENT, "달성 기준의 배점 합계는 100이어야 합니다."),
	CRITERIA_NOT_CONFIRMED(HttpStatus.UNPROCESSABLE_CONTENT, "확정되지 않은 달성 기준입니다.");

	private final HttpStatus status;
	private final String defaultMessage;

	CriteriaErrorCode(HttpStatus status, String defaultMessage) {
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
