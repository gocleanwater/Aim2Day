package com.aim2day.user.auth.validation;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 비밀번호 규칙(API 명세 §3.1). 가입과 비밀번호 재설정이 같은 규칙을 쓰므로 DTO마다 정규식을 복사하지 않고
 * 이 제약 하나를 공유한다. 위반은 모두 reason PASSWORD_POLICY 하나로 보고한다.
 * null은 통과시킨다(누락은 필드의 @NotNull이 REQUIRED로 보고하므로 중복 보고하지 않는다).
 */
@Documented
@Constraint(validatedBy = ValidPassword.Validator.class)
@Target({ ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT })
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidPassword {

	// 메시지를 명세 reason 이름으로 둬야 GlobalExceptionHandler가 그대로 응답의 reason으로 쓴다.
	String message() default "PASSWORD_POLICY";

	Class<?>[] groups() default {};

	Class<? extends Payload>[] payload() default {};

	class Validator implements ConstraintValidator<ValidPassword, String> {

		static final int MIN_LENGTH = 8;
		static final int MAX_LENGTH = 64;

		@Override
		public boolean isValid(String password, ConstraintValidatorContext context) {
			return password == null || satisfies(password);
		}

		/**
		 * 8~64자, 출력 가능한 ASCII(0x21 '!' ~ 0x7E '~')만 허용, 영문 대문자·영문 소문자·숫자·기호를 각각 1자 이상.
		 * ASCII로 제한하는 이유: BCrypt는 앞 72바이트만 처리하는데, ASCII는 1글자가 1바이트라 64자가 항상 64바이트다.
		 * 한글·이모지는 글자당 여러 바이트라 뒷부분이 비교에서 무시될 수 있다. 공백(0x20)도 허용하지 않는다.
		 */
		static boolean satisfies(String password) {
			// 길이 검사를 먼저 해, 비정상적으로 긴 입력을 문자 단위로 훑지 않는다.
			if (password.length() < MIN_LENGTH || password.length() > MAX_LENGTH) {
				return false;
			}
			boolean upper = false;
			boolean lower = false;
			boolean digit = false;
			boolean symbol = false;
			for (int i = 0; i < password.length(); i++) {
				char c = password.charAt(i);
				if (c < 0x21 || c > 0x7E) {
					return false; // 공백·제어문자·비ASCII(한글, 이모지의 서로게이트 쌍 포함)
				}
				if (c >= 'A' && c <= 'Z') {
					upper = true;
				}
				else if (c >= 'a' && c <= 'z') {
					lower = true;
				}
				else if (c >= '0' && c <= '9') {
					digit = true;
				}
				else {
					symbol = true; // 0x21~0x7E 중 영문·숫자가 아닌 문자
				}
			}
			return upper && lower && digit && symbol;
		}
	}
}
