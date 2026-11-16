package com.aim2day.goal.common.exception;

import org.springframework.http.HttpStatus;

/**
 * 오류 코드의 공통 모양. 도메인마다 enum이 이를 구현해 코드를 나눠 가진다.
 * enum이 구현하면 name()은 자동으로 만족되며, 응답의 code 값이 된다.
 * 409/422는 "상태 충돌 / 업무 규칙 위반"으로 구분한다(§2.8).
 */
public interface ErrorCode {

	String name();

	HttpStatus status();

	String defaultMessage();
}
