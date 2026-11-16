package com.aim2day.goal.common.exception;

/**
 * VALIDATION_FAILED의 fieldErrors[].reason 값(API 명세 §2.8).
 * PASSWORD_POLICY·PASSWORD_MISMATCH는 user-service 전용이라 넣지 않았다.
 */
public enum ValidationReason {
	REQUIRED, INVALID_FORMAT, TOO_LONG, OUT_OF_RANGE
}
