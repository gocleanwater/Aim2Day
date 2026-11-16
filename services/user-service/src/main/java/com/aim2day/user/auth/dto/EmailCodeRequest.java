package com.aim2day.user.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Locale;

/**
 * 인증번호 요청. 이메일은 앞뒤 공백을 지우고 소문자로 바꿔 저장·비교한다(명세).
 * 검증 전, 생성 시점에 정규화해야 " A@B.com "이 형식 오류로 거부되지 않고 서비스·Redis 키가 항상 같은 값을 본다.
 */
public record EmailCodeRequest(@NotBlank @Size(max = 255) @Email String email) {

	public EmailCodeRequest {
		if (email != null) {
			email = email.trim().toLowerCase(Locale.ROOT);
		}
	}
}
