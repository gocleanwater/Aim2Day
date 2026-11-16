package com.aim2day.goal.common.exception;

import com.aim2day.goal.common.response.ErrorResponse;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * 컨트롤러에서 올라온 예외를 API 명세 §2.1의 오류 JSON({code, message, details})으로 바꾼다.
 * 이 처리기가 없으면 Spring 기본 오류 형식(timestamp/status/error/path)이 나가 프론트엔드가
 * code로 분기할 수 없다. 필터 단계(401/403)의 오류는 여기까지 오지 않으므로 SecurityConfig의
 * 핸들러가 따로 같은 형식으로 만든다.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

	@ExceptionHandler(BusinessException.class)
	public ResponseEntity<ErrorResponse> handleBusiness(BusinessException e) {
		ErrorCode code = e.getErrorCode();
		return ResponseEntity.status(code.status())
				.body(ErrorResponse.of(code, e.getMessage(), e.getDetails()));
	}

	/** @Valid @RequestBody / @ModelAttribute 검증 실패. MethodArgumentNotValidException도 BindException의 하위 타입이다. */
	@ExceptionHandler(BindException.class)
	public ResponseEntity<ErrorResponse> handleBind(BindException e) {
		List<FieldErrorDetail> errors = new ArrayList<>();
		for (ObjectError error : e.getBindingResult().getAllErrors()) {
			errors.add(toDetail(error));
		}
		return validationFailed(errors);
	}

	/** 컨트롤러 메서드 파라미터(@RequestParam, @PathVariable 등)에 붙인 제약 위반. */
	@ExceptionHandler(HandlerMethodValidationException.class)
	public ResponseEntity<ErrorResponse> handleMethodValidation(HandlerMethodValidationException e) {
		List<FieldErrorDetail> errors = new ArrayList<>();
		e.getParameterValidationResults().forEach(result -> {
			// @Valid 객체 파라미터면 필드 단위 오류(ParameterErrors), 아니면 파라미터 자체의 오류다.
			if (result instanceof ParameterErrors pe) {
				pe.getAllErrors().forEach(err -> errors.add(toDetail(err)));
			} else {
				String paramName = result.getMethodParameter().getParameterName();
				result.getResolvableErrors().forEach(err ->
						errors.add(new FieldErrorDetail(paramName, reasonOf(err).name())));
			}
		});
		return validationFailed(errors);
	}

	/** 서비스 계층 @Validated 등에서 직접 던져지는 Bean Validation 예외. */
	@ExceptionHandler(ConstraintViolationException.class)
	public ResponseEntity<ErrorResponse> handleConstraintViolation(ConstraintViolationException e) {
		List<FieldErrorDetail> errors = new ArrayList<>();
		for (ConstraintViolation<?> v : e.getConstraintViolations()) {
			String path = v.getPropertyPath().toString();
			String field = path.substring(path.lastIndexOf('.') + 1);
			String constraint = v.getConstraintDescriptor().getAnnotation().annotationType().getSimpleName();
			errors.add(new FieldErrorDetail(field, reasonOf(constraint, v.getMessage(), v).name()));
		}
		return validationFailed(errors);
	}

	@ExceptionHandler(MissingServletRequestParameterException.class)
	public ResponseEntity<ErrorResponse> handleMissingParam(MissingServletRequestParameterException e) {
		return validationFailed(List.of(new FieldErrorDetail(e.getParameterName(), ValidationReason.REQUIRED.name())));
	}

	/** 쿼리·경로 값을 타입(날짜, 숫자, enum)으로 바꾸지 못함. 예: ?month=abc */
	@ExceptionHandler(MethodArgumentTypeMismatchException.class)
	public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
		return validationFailed(List.of(new FieldErrorDetail(e.getName(), ValidationReason.INVALID_FORMAT.name())));
	}

	/** JSON 문법 오류, 알 수 없는 enum 값 등 본문을 읽지 못함(명세: MALFORMED_REQUEST, details null). */
	@ExceptionHandler(HttpMessageNotReadableException.class)
	public ResponseEntity<ErrorResponse> handleNotReadable(HttpMessageNotReadableException e) {
		return ResponseEntity.status(ErrorCode.MALFORMED_REQUEST.status())
				.body(ErrorResponse.of(ErrorCode.MALFORMED_REQUEST));
	}

	/**
	 * 낙관적 락 충돌의 안전망. 정상 경로는 서비스가 version을 비교해 currentVersion이 담긴
	 * BusinessException(VERSION_CONFLICT)을 던지는 것이고, 비교를 통과한 뒤 다른 인스턴스가
	 * 먼저 커밋해 UPDATE 시점에 충돌한 경우만 여기로 온다. 이때는 최신 version을 알 수 없어 details가 null이다.
	 */
	@ExceptionHandler(OptimisticLockingFailureException.class)
	public ResponseEntity<ErrorResponse> handleOptimisticLock(OptimisticLockingFailureException e) {
		return ResponseEntity.status(ErrorCode.VERSION_CONFLICT.status())
				.body(ErrorResponse.of(ErrorCode.VERSION_CONFLICT));
	}

	/**
	 * 나머지 전부. Spring MVC가 만든 4xx(없는 경로 404, 405, 415 등)는 상태를 유지하고,
	 * 그 외에는 500 INTERNAL_ERROR로 숨긴다. 스택 트레이스·예외 메시지는 응답에 싣지 않는다(정보 노출 방지).
	 */
	@ExceptionHandler(Exception.class)
	public ResponseEntity<ErrorResponse> handleAny(Exception e) throws Exception {
		// 인증·인가 예외는 Spring Security(ExceptionTranslationFilter)가 401/403으로 처리해야 한다.
		// 여기서 500으로 삼키면 인증 실패가 서버 오류로 바뀐다.
		if (e instanceof AccessDeniedException || e instanceof AuthenticationException) {
			throw e;
		}
		if (e instanceof org.springframework.web.ErrorResponse springError
				&& springError.getStatusCode().is4xxClientError()) {
			// 405의 Allow, 415의 Accept 등 표준 헤더를 잃지 않도록 예외가 가진 헤더를 그대로 복사한다.
			return ResponseEntity.status(springError.getStatusCode())
					.headers(springError.getHeaders())
					.body(fromClientStatus(springError.getStatusCode()).getBody());
		}
		log.error("처리되지 않은 예외", e);
		return ResponseEntity.status(ErrorCode.INTERNAL_ERROR.status())
				.body(ErrorResponse.of(ErrorCode.INTERNAL_ERROR));
	}

	/**
	 * HTTP 상태만 아는 4xx를 명세 코드로 옮긴다(ErrorController도 같은 규칙을 쓴다).
	 * 404는 RESOURCE_NOT_FOUND, 명세에 코드가 없는 405·415 등은 MALFORMED_REQUEST로 두되 HTTP 상태는 유지한다.
	 */
	public static ResponseEntity<ErrorResponse> fromClientStatus(HttpStatusCode status) {
		ErrorCode code = status.value() == 404 ? ErrorCode.RESOURCE_NOT_FOUND : ErrorCode.MALFORMED_REQUEST;
		return ResponseEntity.status(status).body(ErrorResponse.of(code));
	}

	private ResponseEntity<ErrorResponse> validationFailed(List<FieldErrorDetail> errors) {
		return ResponseEntity.status(ErrorCode.VALIDATION_FAILED.status())
				.body(ErrorResponse.of(ErrorCode.VALIDATION_FAILED, Map.of("fieldErrors", errors)));
	}

	private FieldErrorDetail toDetail(ObjectError error) {
		String field = error instanceof FieldError fe ? fe.getField() : error.getObjectName();
		return new FieldErrorDetail(field, reasonOf(error).name());
	}

	/** Spring의 검증 오류에서 제약 이름(NotBlank, Size 등)과 원본 ConstraintViolation을 꺼내 reason을 정한다. */
	private ValidationReason reasonOf(MessageSourceResolvable error) {
		// 오류 코드 배열은 구체적 -> 일반 순이라 마지막이 제약 이름이다(예: [NotBlank.title, NotBlank]).
		String[] codes = error.getCodes();
		String constraint = codes != null && codes.length > 0 ? codes[codes.length - 1] : "";
		ConstraintViolation<?> violation = null;
		if (error instanceof ObjectError oe && oe.contains(ConstraintViolation.class)) {
			violation = oe.unwrap(ConstraintViolation.class);
		}
		return reasonOf(constraint, error.getDefaultMessage(), violation);
	}

	/**
	 * 제약 이름 -> reason 매핑. 필수값 계열은 REQUIRED, 길이 초과는 TOO_LONG, 범위 계열은 OUT_OF_RANGE,
	 * 그 외(Pattern, 타입 불일치 등)는 INVALID_FORMAT이다. 제약의 message를 reason 이름
	 * (예: @Pattern(message = "INVALID_FORMAT"))으로 직접 지정하면 그 값을 우선한다.
	 */
	private ValidationReason reasonOf(String constraintName, String message, ConstraintViolation<?> violation) {
		for (ValidationReason r : ValidationReason.values()) {
			if (r.name().equals(message)) {
				return r;
			}
		}
		return switch (constraintName) {
			case "NotNull", "NotBlank", "NotEmpty" -> ValidationReason.REQUIRED;
			case "Size", "Length" -> sizeReason(violation);
			case "Min", "Max", "DecimalMin", "DecimalMax", "Range", "Positive", "PositiveOrZero",
					"Negative", "NegativeOrZero", "Digits" -> ValidationReason.OUT_OF_RANGE;
			default -> ValidationReason.INVALID_FORMAT;
		};
	}

	/** @Size는 최대 초과면 TOO_LONG, 최소 미달이면 OUT_OF_RANGE로 나눈다. 판단 불가면 TOO_LONG. */
	private ValidationReason sizeReason(ConstraintViolation<?> violation) {
		if (violation == null) {
			return ValidationReason.TOO_LONG;
		}
		Object value = violation.getInvalidValue();
		Object max = violation.getConstraintDescriptor().getAttributes().get("max");
		int length = value instanceof CharSequence cs ? cs.length()
				: value instanceof Collection<?> c ? c.size() : -1;
		if (max instanceof Integer m && length >= 0 && length <= m) {
			return ValidationReason.OUT_OF_RANGE;
		}
		return ValidationReason.TOO_LONG;
	}
}
