package com.aim2day.user.auth.dto;

import com.aim2day.user.auth.validation.ValidPassword;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Locale;

/** 인증 도메인의 요청·응답 형식 모음. 가입·인증번호·로그인·비밀번호 재설정 DTO를 한 파일에 둔다. */
public final class AuthDtos {

	private AuthDtos() {
	}

	/**
	 * 인증번호 요청. 이메일은 앞뒤 공백을 지우고 소문자로 바꿔 저장·비교한다(명세).
	 * 검증 전, 생성 시점에 정규화해야 " A@B.com "이 형식 오류로 거부되지 않고 서비스·Redis 키가 항상 같은 값을 본다.
	 */
	public static record EmailCodeRequest(@NotBlank @Size(max = 255) @Email String email) {

		public EmailCodeRequest {
			if (email != null) {
				email = email.trim().toLowerCase(Locale.ROOT);
			}
		}

		/** record 기본 toString은 필드 값을 그대로 출력한다. 요청·응답 객체는 Spring MVC의 DEBUG/TRACE 로그("Read ... to [...]", "Writing [...]")에
		 * toString으로 찍힐 수 있으므로 민감값을 가린다. */
		@Override
		public String toString() {
			return "EmailCodeRequest[email=***]";
		}
	}

	public static record EmailCodeResponse(int expiresInSeconds) {
	}

	/** 인증번호 확인. 번호는 앞의 0을 유지해야 하므로 문자열이고, 6자리 숫자만 허용한다. */
	public static record EmailCodeVerifyRequest(
			@NotBlank @Size(max = 255) @Email String email,
			// [0-9]를 명시해 ASCII 숫자만 허용한다(다른 문자 체계의 숫자를 받지 않는다).
			@NotBlank @Pattern(regexp = "[0-9]{6}") String code) {

		public EmailCodeVerifyRequest {
			if (email != null) {
				email = email.trim().toLowerCase(Locale.ROOT);
			}
		}

		/** record 기본 toString은 필드 값을 그대로 출력한다. 요청·응답 객체는 Spring MVC의 DEBUG/TRACE 로그("Read ... to [...]", "Writing [...]")에
		 * toString으로 찍힐 수 있으므로 민감값을 가린다. */
		@Override
		public String toString() {
			return "EmailCodeVerifyRequest[email=***, code=***]";
		}
	}

	public static record EmailCodeVerifyResponse(String signupToken, int expiresInSeconds) {

		/** record 기본 toString은 필드 값을 그대로 출력한다. 요청·응답 객체는 Spring MVC의 DEBUG/TRACE 로그("Read ... to [...]", "Writing [...]")에
		 * toString으로 찍힐 수 있으므로 민감값을 가린다. */
		@Override
		public String toString() {
			return "EmailCodeVerifyResponse[signupToken=***, expiresInSeconds=" + expiresInSeconds + "]";
		}
	}

	/**
	 * 로그인 요청. 이메일은 가입·인증번호 요청과 같은 방식(앞뒤 공백 제거 + 소문자)으로 생성 시점에 정규화한다.
	 * 가입 때 정규화된 값이 저장되어 있으므로 같은 규칙으로 맞춰야 " User@Example.com "으로도 로그인된다.
	 *
	 * <p>비밀번호는 규칙(대소문자·숫자·기호)을 다시 검사하지 않는다. 로그인에서 규칙을 검사하면 "규칙에 맞지 않는다"는
	 * 응답으로 계정 정보를 추측할 수 있고, 규칙이 바뀌기 전에 가입한 사용자가 로그인하지 못한다. 다만 길이는 64자(가입 규칙의
	 * 최대)로 제한해 BCrypt에 터무니없이 긴 입력이 들어오지 않게 한다. 비밀번호는 trim하지 않는다(공백도 비밀번호의 일부).
	 */
	public static record LoginRequest(
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

	public static record LoginResponse(Long id, String email, String username) {

		/** record 기본 toString은 필드 값을 그대로 출력한다. 요청·응답 객체는 Spring MVC의 DEBUG/TRACE 로그("Read ... to [...]", "Writing [...]")에
		 * toString으로 찍힐 수 있으므로 민감값을 가린다. */
		@Override
		public String toString() {
			return "LoginResponse[id=" + id + ", email=***, username=***]";
		}
	}

	/**
	 * 재설정 링크 요청. 이메일은 가입·로그인과 같은 규칙(앞뒤 공백 제거 + 소문자)으로 생성 시점에 정규화한다.
	 * 저장된 이메일이 정규화된 값이라 같은 규칙으로 맞춰야 계정을 찾고, 재요청 제한 키도 항상 같은 값을 본다.
	 */
	public static record PasswordResetLinkRequest(@NotBlank @Size(max = 255) @Email String email) {

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

	/**
	 * 새 비밀번호 제출. 비밀번호 규칙과 확인 일치는 컨트롤러의 @Valid가 서비스 호출 전에 검사한다.
	 * 그래서 규칙 위반 요청은 토큰을 소비하기 전에 400으로 끝나고, 같은 링크로 다시 제출할 수 있다.
	 */
	@PasswordsMatch
	public static record PasswordResetRequest(
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

	/** 가입 요청. 이메일은 받지 않고 가입권에서 꺼낸다. */
	@PasswordsMatch
	public static record SignupRequest(
			@NotBlank String signupToken,
			// 공백만 있는 이름은 거부한다("공백 제외 1자 이상").
			@NotBlank @Size(max = 50) String username,
			@NotNull @ValidPassword String password,
			@NotNull String passwordConfirm) {

		/** record 기본 toString은 필드 값을 그대로 출력한다. 요청·응답 객체는 Spring MVC의 DEBUG/TRACE 로그("Read ... to [...]", "Writing [...]")에
		 * toString으로 찍힐 수 있으므로 민감값을 가린다. */
		@Override
		public String toString() {
			return "SignupRequest[signupToken=***, username=***, password=***, passwordConfirm=***]";
		}
	}
}
