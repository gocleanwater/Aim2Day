package com.aim2day.user.auth.dto;

import com.aim2day.user.auth.validation.ValidPassword;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 새 비밀번호 제출. 비밀번호 규칙과 확인 일치는 컨트롤러의 @Valid가 서비스 호출 전에 검사한다.
 * 그래서 규칙 위반 요청은 토큰을 소비하기 전에 400으로 끝나고, 같은 링크로 다시 제출할 수 있다.
 */
@PasswordsMatch
public record PasswordResetRequest(
		@NotBlank String token,
		@NotNull @ValidPassword String newPassword,
		@NotNull String newPasswordConfirm) {

	/** record 기본 toString은 필드 값을 그대로 출력한다. 요청·응답 객체는 Spring MVC의 DEBUG/TRACE 로그("Read ... to [...]", "Writing [...]")에
	 * toString으로 찍힐 수 있으므로 민감값을 가린다. */
	@Override
	public String toString() {
		return "PasswordResetRequest[token=***, newPassword=***, newPasswordConfirm=***]";
	}
}
