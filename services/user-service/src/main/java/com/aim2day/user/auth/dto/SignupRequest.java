package com.aim2day.user.auth.dto;

import com.aim2day.user.auth.validation.ValidPassword;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 가입 요청. 이메일은 받지 않고 가입권에서 꺼낸다. */
@PasswordsMatch
public record SignupRequest(
		@NotBlank String signupToken,
		// 공백만 있는 이름은 거부한다("공백 제외 1자 이상").
		@NotBlank @Size(max = 50) String username,
		@NotNull @ValidPassword String password,
		@NotNull String passwordConfirm) {
}
