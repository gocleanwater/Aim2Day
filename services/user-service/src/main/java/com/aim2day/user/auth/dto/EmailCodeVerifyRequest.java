package com.aim2day.user.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Locale;

/** 인증번호 확인. 번호는 앞의 0을 유지해야 하므로 문자열이고, 6자리 숫자만 허용한다. */
public record EmailCodeVerifyRequest(
		@NotBlank @Size(max = 255) @Email String email,
		// [0-9]를 명시해 ASCII 숫자만 허용한다(다른 문자 체계의 숫자를 받지 않는다).
		@NotBlank @Pattern(regexp = "[0-9]{6}") String code) {

	public EmailCodeVerifyRequest {
		if (email != null) {
			email = email.trim().toLowerCase(Locale.ROOT);
		}
	}
}
