package com.aim2day.user.auth.validation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ValidPasswordTests {

	private static boolean ok(String p) {
		return ValidPassword.Validator.satisfies(p);
	}

	@Test
	void acceptsValidPasswords() {
		assertThat(ok("Aim2day!2026")).isTrue();
		assertThat(ok("~~~~Aa1~")).isTrue(); // 8자 경계, 0x7E
		assertThat(ok("!1A" + "b".repeat(61))).isTrue(); // 64자 경계
	}

	@Test
	void rejectsLengthViolations() {
		assertThat(ok("Aa1!aaa")).isFalse(); // 7자
		assertThat(ok("Aa1!" + "a".repeat(61))).isFalse(); // 65자
	}

	@Test
	void rejectsMissingCharacterClass() {
		assertThat(ok("12345678!!")).isFalse();
		assertThat(ok("abcdefgh!!")).isFalse();
		assertThat(ok("abcdefgh12")).isFalse();
		assertThat(ok("abcdefg1!")).isFalse(); // 소문자만(대문자 없음)
		assertThat(ok("ABCDEFG1!")).isFalse(); // 대문자만(소문자 없음)
		assertThat(ok("Abcdefgh1")).isFalse(); // 기호 없음
		assertThat(ok("Abcdefgh!")).isFalse(); // 숫자 없음
	}

	@Test
	void rejectsSpaceHangulEmojiAndControl() {
		assertThat(ok("Aim2day! 2026")).isFalse();
		assertThat(ok("Aim2day!한글비밀")).isFalse();
		assertThat(ok("Aim2day!2026😀")).isFalse();
		assertThat(ok("Aim2day!2026\t")).isFalse();
		assertThat(ok("Aim2day!2026\u007F")).isFalse(); // DEL(0x7F)
	}
}
