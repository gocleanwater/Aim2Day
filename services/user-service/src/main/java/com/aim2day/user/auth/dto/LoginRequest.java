package com.aim2day.user.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Locale;

/**
 * 로그인 요청. 이메일은 가입·인증번호 요청과 같은 방식(앞뒤 공백 제거 + 소문자)으로 생성 시점에 정규화한다.
 * 가입 때 정규화된 값이 저장되어 있으므로 같은 규칙으로 맞춰야 " User@Example.com "으로도 로그인된다.
 *
 * <p>비밀번호는 규칙(대소문자·숫자·기호)을 다시 검사하지 않는다. 로그인에서 규칙을 검사하면 "규칙에 맞지 않는다"는
 * 응답으로 계정 정보를 추측할 수 있고, 규칙이 바뀌기 전에 가입한 사용자가 로그인하지 못한다. 다만 길이는 64자(가입 규칙의
 * 최대)로 제한해 BCrypt에 터무니없이 긴 입력이 들어오지 않게 한다. 비밀번호는 trim하지 않는다(공백도 비밀번호의 일부).
 */
public record LoginRequest(
		@NotBlank @Size(max = 255) @Email String email,
		@NotBlank @Size(max = 64) String password) {

	public LoginRequest {
		if (email != null) {
			email = email.trim().toLowerCase(Locale.ROOT);
		}
	}

	/** record 기본 toString은 비밀번호를 그대로 출력하므로 실수로 로그에 남아도 노출되지 않게 막는다. */
	@Override
	public String toString() {
		return "LoginRequest[email=***, password=***]";
	}
}
