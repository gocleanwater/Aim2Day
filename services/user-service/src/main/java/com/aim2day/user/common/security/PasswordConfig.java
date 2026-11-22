package com.aim2day.user.common.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 비밀번호 해시 인코더 빈 설정. 가입(SignupService), 로그인(LoginService), 재설정(PasswordResetService)이
 * 같은 인코더를 주입받아 해시 생성과 비교 방식이 항상 일치하게 한다.
 */
@Configuration
public class PasswordConfig {

	/** 비밀번호는 BCrypt(솔트 내장, 느린 해시)로만 저장한다. 로그인 단위에서도 같은 빈으로 matches 한다. */
	@Bean
	PasswordEncoder passwordEncoder() {
		return new BCryptPasswordEncoder();
	}
}
