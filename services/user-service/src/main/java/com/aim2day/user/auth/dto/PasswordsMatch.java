package com.aim2day.user.auth.dto;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 비밀번호와 확인 값이 같아야 한다. 위반은 passwordConfirm 필드의 PASSWORD_MISMATCH로 보고한다. */
@Documented
@Constraint(validatedBy = PasswordsMatch.Validator.class)
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface PasswordsMatch {

	// 메시지를 명세 reason 이름으로 둬야 GlobalExceptionHandler가 그대로 응답의 reason으로 쓴다.
	String message() default "PASSWORD_MISMATCH";

	Class<?>[] groups() default {};

	Class<? extends Payload>[] payload() default {};

	class Validator implements ConstraintValidator<PasswordsMatch, SignupRequest> {

		@Override
		public boolean isValid(SignupRequest request, ConstraintValidatorContext context) {
			// 비어 있는 값은 각 필드의 @NotNull이 REQUIRED로 잡는다. 여기서 중복 보고하지 않는다.
			if (request.password() == null || request.passwordConfirm() == null
					|| request.password().equals(request.passwordConfirm())) {
				return true;
			}
			// 클래스 수준 위반은 기본적으로 필드 없는 전역 오류가 된다. 프론트가 어느 입력칸에 표시할지 알 수 있게
			// passwordConfirm 속성에 붙인다.
			context.disableDefaultConstraintViolation();
			context.buildConstraintViolationWithTemplate(context.getDefaultConstraintMessageTemplate())
					.addPropertyNode("passwordConfirm")
					.addConstraintViolation();
			return false;
		}
	}
}
