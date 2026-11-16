package com.aim2day.user.auth.dto;

public record SignupResponse(long userId, String email) {

	/** record 기본 toString은 필드 값을 그대로 출력한다. 요청·응답 객체는 Spring MVC의 DEBUG/TRACE 로그("Read ... to [...]", "Writing [...]")에
	 * toString으로 찍힐 수 있으므로 민감값을 가린다. */
	@Override
	public String toString() {
		return "SignupResponse[userId=" + userId + ", email=***]";
	}
}
