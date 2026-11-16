package com.aim2day.user.common.response;

import jakarta.validation.ConstraintViolation;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 컨트롤러에서 발생한 예외를 모두 명세의 오류 JSON으로 바꾼다.
 * MVC 안쪽 예외만 처리한다. 필터 단계(401, CSRF 403)는 Spring Security 핸들러가 맡는다.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

	/** API 명세 §2.8의 VALIDATION_FAILED reason 값. 제약 조건의 message를 이 값으로 지정하면 그대로 쓴다. */
	private static final Set<String> KNOWN_REASONS = Set.of(
			"REQUIRED", "INVALID_FORMAT", "TOO_LONG", "OUT_OF_RANGE", "PASSWORD_POLICY", "PASSWORD_MISMATCH");

	@ExceptionHandler(BusinessException.class)
	ResponseEntity<ErrorResponse> handleBusiness(BusinessException e) {
		return respond(e.errorCode(), e.details());
	}

	/** @RequestBody @Valid 실패. MethodArgumentNotValidException은 BindException의 하위 타입이다. */
	@ExceptionHandler(BindException.class)
	ResponseEntity<ErrorResponse> handleBind(BindException e) {
		List<Map<String, String>> fieldErrors = new ArrayList<>();
		for (FieldError fe : e.getBindingResult().getFieldErrors()) {
			fieldErrors.add(fieldError(fe.getField(), reasonOf(fe.getCode(), fe.getDefaultMessage(), violationOf(fe))));
		}
		// 필드에 속하지 않는 클래스 수준 검증(예: 비밀번호 확인 불일치)은 objectName을 field로 쓴다.
		e.getBindingResult().getGlobalErrors().forEach(ge ->
				fieldErrors.add(fieldError(ge.getObjectName(), reasonOf(ge.getCode(), ge.getDefaultMessage(), violationOf(ge)))));
		return validationFailed(fieldErrors);
	}

	/** @RequestParam, @PathVariable 등 메서드 파라미터 검증 실패(Spring 6.1+). */
	@ExceptionHandler(HandlerMethodValidationException.class)
	ResponseEntity<ErrorResponse> handleMethodValidation(HandlerMethodValidationException e) {
		List<Map<String, String>> fieldErrors = new ArrayList<>();
		e.getParameterValidationResults().forEach(result -> {
			if (result instanceof ParameterErrors pe) {
				// @Valid 객체 인자는 개별 오류의 필드명(DTO 필드)을 쓴다. 메서드 인자명으로 덮으면 어느 필드인지 잃는다.
				pe.getFieldErrors().forEach(fe -> fieldErrors.add(
						fieldError(fe.getField(), reasonOf(fe.getCode(), fe.getDefaultMessage(), violationOf(fe)))));
				pe.getGlobalErrors().forEach(ge -> fieldErrors.add(
						fieldError(ge.getObjectName(), reasonOf(ge.getCode(), ge.getDefaultMessage(), violationOf(ge)))));
				return;
			}
			// @RequestParam 등에 직접 붙은 제약. 원본 ConstraintViolation을 꺼내야 Size의 min/max를 구분할 수 있다.
			String name = result.getMethodParameter().getParameterName();
			for (MessageSourceResolvable error : result.getResolvableErrors()) {
				ConstraintViolation<?> violation = unwrapViolation(result, error);
				fieldErrors.add(fieldError(name == null ? "unknown" : name,
						reasonOf(constraintName(error), error.getDefaultMessage(), violation)));
			}
		});
		return validationFailed(fieldErrors);
	}

	/** 검증 결과에서 원본 위반 정보를 꺼낸다. 꺼낼 수 없으면 null. */
	private static ConstraintViolation<?> unwrapViolation(ParameterValidationResult result, MessageSourceResolvable error) {
		try {
			return result.unwrap(error, ConstraintViolation.class);
		}
		catch (RuntimeException ex) {
			return null;
		}
	}

	@ExceptionHandler(MissingServletRequestParameterException.class)
	ResponseEntity<ErrorResponse> handleMissingParam(MissingServletRequestParameterException e) {
		return validationFailed(List.of(fieldError(e.getParameterName(), "REQUIRED")));
	}

	@ExceptionHandler(MethodArgumentTypeMismatchException.class)
	ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
		return validationFailed(List.of(fieldError(e.getName(), "INVALID_FORMAT")));
	}

	/** JSON 파싱 실패, 알 수 없는 열거값 등. 요청 원문(비밀번호가 있을 수 있음)은 로그에 남기지 않는다. */
	@ExceptionHandler(HttpMessageNotReadableException.class)
	ResponseEntity<ErrorResponse> handleNotReadable(HttpMessageNotReadableException e) {
		return respond(ErrorCode.MALFORMED_REQUEST, null);
	}

	/** 없는 경로. 정적 리소스 핸들러가 잡으면 NoResourceFoundException, 아니면 NoHandlerFoundException이다. */
	@ExceptionHandler({ NoResourceFoundException.class, NoHandlerFoundException.class })
	ResponseEntity<ErrorResponse> handleNotFound(Exception e) {
		return respond(ErrorCode.RESOURCE_NOT_FOUND, null);
	}

	/**
	 * 명세에 405, 415 전용 코드가 없다. 500으로 뭉개지 않도록 HTTP 상태는 유지하고
	 * 요청 형식 오류 코드(MALFORMED_REQUEST)로 응답한다(가정).
	 */
	@ExceptionHandler({ HttpRequestMethodNotSupportedException.class, HttpMediaTypeNotSupportedException.class })
	ResponseEntity<ErrorResponse> handleUnsupported(org.springframework.web.ErrorResponse e) {
		// 405의 Allow, 415의 Accept 같은 헤더는 예외가 들고 있다. 빠뜨리면 클라이언트가 허용 메서드를 알 수 없다.
		return ResponseEntity.status(e.getStatusCode()).headers(e.getHeaders())
				.body(ErrorResponse.of(ErrorCode.MALFORMED_REQUEST));
	}

	/**
	 * 위에서 개별 처리하지 않은 Spring MVC 예외(필수 헤더 누락, 406 등)는 스스로 4xx 상태를 가진다.
	 * 마지막 방어선이 500으로 덮지 않도록 원래 상태를 유지하고 공통 본문으로 바꾼다.
	 */
	private static ResponseEntity<ErrorResponse> fromSpringError(org.springframework.web.ErrorResponse e) {
		return ResponseEntity.status(e.getStatusCode()).headers(e.getHeaders())
				.body(ErrorResponse.of(codeFor(e.getStatusCode())));
	}

	/** HTTP 상태만 아는 4xx를 명세 코드로 옮긴다. 404 외에는 전용 코드가 없어 MALFORMED_REQUEST로 둔다(가정). */
	public static ErrorCode codeFor(HttpStatusCode status) {
		return status.value() == 404 ? ErrorCode.RESOURCE_NOT_FOUND : ErrorCode.MALFORMED_REQUEST;
	}

	/** 마지막 방어선. 스택은 서버 로그에만 남기고 응답에는 일반 문구만 내보낸다. */
	@ExceptionHandler(Exception.class)
	ResponseEntity<ErrorResponse> handleUnexpected(Exception e) throws Exception {
		// 인증·인가 예외는 다시 던져 Spring Security(ExceptionTranslationFilter)가 401 등으로 처리하게 한다. 500으로 삼키지 않는다.
		if (e instanceof AccessDeniedException || e instanceof AuthenticationException) {
			throw e;
		}
		// 개별 처리하지 않은 Spring MVC 예외(필수 헤더 누락 등)는 4xx를 유지한다. 500으로 덮으면 클라이언트 오류가 서버 오류로 보인다.
		if (e instanceof org.springframework.web.ErrorResponse springError
				&& springError.getStatusCode().is4xxClientError()) {
			return fromSpringError(springError);
		}
		log.error("처리되지 않은 예외", e);
		return respond(ErrorCode.INTERNAL_ERROR, null);
	}

	private static ResponseEntity<ErrorResponse> respond(ErrorCode code, Object details) {
		return ResponseEntity.status(code.status()).body(ErrorResponse.of(code, details));
	}

	/** PASSWORD_POLICY reason일 때 응답 message로 쓰는 규칙 전문(API 명세 §2.8). */
	static final String PASSWORD_POLICY_MESSAGE = "비밀번호는 8~64자이며 영문 대문자, 영문 소문자, 숫자, 기호를 각각 1자 이상 포함해야 합니다. 공백·한글·이모지는 쓸 수 없습니다.";

	private static ResponseEntity<ErrorResponse> validationFailed(List<Map<String, String>> fieldErrors) {
		// 사용자가 비밀번호 규칙을 어겼다면 "입력값이 올바르지 않습니다" 같은 일반 문구로는 무엇을 고칠지 알 수 없으므로,
		// fieldErrors에 PASSWORD_POLICY가 하나라도 있으면 최상위 message를 규칙 전문으로 바꾼다.
		// 다른 필드 오류가 함께 있어도 비밀번호 쪽이 구체적 안내이므로 우선한다(각 필드의 상세는 details에 그대로 있다).
		// 그 밖의 경우는 기존 VALIDATION_FAILED 문구를 유지한다.
		boolean passwordPolicy = fieldErrors.stream().anyMatch(fe -> "PASSWORD_POLICY".equals(fe.get("reason")));
		String message = passwordPolicy ? PASSWORD_POLICY_MESSAGE : ErrorCode.VALIDATION_FAILED.message();
		return ResponseEntity.status(ErrorCode.VALIDATION_FAILED.status())
				.body(new ErrorResponse(ErrorCode.VALIDATION_FAILED.name(), message, Map.of("fieldErrors", fieldErrors)));
	}

	private static Map<String, String> fieldError(String field, String reason) {
		return Map.of("field", field, "reason", reason);
	}

	private static String constraintName(MessageSourceResolvable error) {
		String[] codes = error.getCodes();
		// codes[0]은 "NotBlank.user.email" 꼴이라 첫 마디가 제약 조건 이름이다.
		return codes == null || codes.length == 0 ? null : codes[0].split("\\.")[0];
	}

	/** Spring 검증 오류에 담긴 원본 ConstraintViolation(없으면 null). Size의 min/max 구분에 쓴다. */
	private static ConstraintViolation<?> violationOf(ObjectError error) {
		return error.contains(ConstraintViolation.class) ? error.unwrap(ConstraintViolation.class) : null;
	}

	/**
	 * 제약 조건 -> 명세 reason 매핑.
	 * 1) 제약의 message가 명세 reason 이름이면 그대로 쓴다(예: @Pattern(message = "PASSWORD_POLICY")).
	 * 2) 아니면 제약 종류로 추정한다. Size는 최대 초과만 TOO_LONG, 최소 미달은 OUT_OF_RANGE다.
	 */
	private static String reasonOf(String constraint, String message, ConstraintViolation<?> violation) {
		if (message != null && KNOWN_REASONS.contains(message)) {
			return message;
		}
		if (constraint == null) {
			return "INVALID_FORMAT";
		}
		return switch (constraint) {
			case "NotNull", "NotBlank", "NotEmpty" -> "REQUIRED";
			case "Size", "Length" -> sizeReason(violation);
			case "Min", "Max", "Range", "DecimalMin", "DecimalMax", "Positive", "PositiveOrZero",
					"Negative", "NegativeOrZero" -> "OUT_OF_RANGE";
			default -> "INVALID_FORMAT"; // Email, Pattern 등
		};
	}

	/** 값 길이가 max 이하인데 위반이면 min 미달이다. 판단할 수 없으면 TOO_LONG으로 둔다. */
	private static String sizeReason(ConstraintViolation<?> violation) {
		if (violation == null) {
			return "TOO_LONG";
		}
		Object value = violation.getInvalidValue();
		Object max = violation.getConstraintDescriptor().getAttributes().get("max");
		int length = value instanceof CharSequence cs ? cs.length()
				: value instanceof Collection<?> c ? c.size() : -1;
		return max instanceof Integer m && length >= 0 && length <= m ? "OUT_OF_RANGE" : "TOO_LONG";
	}
}
