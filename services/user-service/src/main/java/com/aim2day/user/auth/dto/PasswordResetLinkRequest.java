package com.aim2day.user.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Locale;

/**
 * 재설정 링크 요청. 이메일은 가입·로그인과 같은 규칙(앞뒤 공백 제거 + 소문자)으로 생성 시점에 정규화한다.
 * 저장된 이메일이 정규화된 값이라 같은 규칙으로 맞춰야 계정을 찾고, 재요청 제한 키도 항상 같은 값을 본다.
 */
public record PasswordResetLinkRequest(@NotBlank @Size(max = 255) @Email String email) {

	public PasswordResetLinkRequest {
		if (email != null) {
			email = email.trim().toLowerCase(Locale.ROOT);
		}
	}

	/** record 기본 toString은 필드 값을 그대로 출력한다. 요청·응답 객체는 Spring MVC의 DEBUG/TRACE 로그("Read ... to [...]", "Writing [...]")에
	 * toString으로 찍힐 수 있으므로 민감값을 가린다. */
	@Override
	public String toString() {
		return "PasswordResetLinkRequest[email=***]";
	}
}
