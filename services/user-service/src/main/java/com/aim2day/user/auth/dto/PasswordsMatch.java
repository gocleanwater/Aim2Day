package com.aim2day.user.auth.dto;

import com.aim2day.user.auth.dto.AuthDtos.PasswordResetRequest;
import com.aim2day.user.auth.dto.AuthDtos.SignupRequest;
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
 * 비밀번호와 확인 값이 같아야 한다. 가입(SignupRequest)과 재설정(PasswordResetRequest)이 함께 쓴다.
 * 위반은 확인 입력칸 필드(passwordConfirm / newPasswordConfirm)의 PASSWORD_MISMATCH로 보고한다.
 * 필드 이름이 DTO마다 달라 검증기를 대상 타입별로 두고, 비교·보고 방식은 Checker로 공유한다.
 */
@Documented
@Constraint(validatedBy = { PasswordsMatch.Validator.class, PasswordsMatch.ResetValidator.class })
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
			return Checker.check(request.password(), request.passwordConfirm(), "passwordConfirm", context);
		}
	}

	class ResetValidator implements ConstraintValidator<PasswordsMatch, PasswordResetRequest> {

		@Override
		public boolean isValid(PasswordResetRequest request, ConstraintValidatorContext context) {
			return Checker.check(request.newPassword(), request.newPasswordConfirm(), "newPasswordConfirm", context);
		}
	}

	/** 어노테이션 안에는 메서드 본문을 둘 수 없어 공유 로직을 중첩 클래스에 둔다. */
	final class Checker {

		private Checker() {
		}

		static boolean check(String password, String confirm, String confirmField,
				ConstraintValidatorContext context) {
			// 비어 있는 값은 각 필드의 @NotNull이 REQUIRED로 잡는다. 여기서 중복 보고하지 않는다.
			if (password == null || confirm == null || password.equals(confirm)) {
				return true;
			}
			// 클래스 수준 위반은 기본적으로 필드 없는 전역 오류가 된다. 프론트가 어느 입력칸에 표시할지 알 수 있게
			// 확인 입력칸 속성에 붙인다.
			context.disableDefaultConstraintViolation();
			context.buildConstraintViolationWithTemplate(context.getDefaultConstraintMessageTemplate())
					.addPropertyNode(confirmField)
					.addConstraintViolation();
			return false;
		}
	}
}
