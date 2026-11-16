package com.aim2day.user.support;

import com.aim2day.user.common.response.ApiResponse;
import com.aim2day.user.common.response.BusinessException;
import com.aim2day.user.common.response.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** 테스트 소스에만 있는 컨트롤러. 운영 jar에는 포함되지 않는다. */
@RestController
public class TestApiController {

	public record Body(@NotBlank String name, @Size(max = 5) String nick, @Size(min = 2, max = 8) String tag,
			@Pattern(regexp = "^[a-z]+$", message = "PASSWORD_POLICY") String pw) {
	}

	@GetMapping("/api/test/whoami")
	ApiResponse<Object> whoami(Authentication authentication) {
		return ApiResponse.success(authentication.getPrincipal());
	}

	@PostMapping("/api/test/echo")
	ApiResponse<Body> echo(@Valid @RequestBody Body body) {
		return ApiResponse.success(body);
	}

	@GetMapping("/api/test/time")
	ApiResponse<Map<String, Object>> time() {
		Instant instant = Instant.parse("2026-11-12T01:23:45Z");
		return ApiResponse.success(Map.of(
				"instant", instant,
				"offset", OffsetDateTime.ofInstant(instant, ZoneOffset.UTC),
				"date", Date.from(instant)));
	}

	/** 필수 헤더 누락 시 Spring MVC의 MissingRequestHeaderException(4xx ErrorResponse 구현)이 난다. */
	@GetMapping("/api/test/header")
	ApiResponse<String> header(@RequestHeader("X-Required") String value) {
		return ApiResponse.success(value);
	}

	@GetMapping("/api/test/boom")
	void boom() {
		throw new IllegalStateException("secret-detail");
	}

	@GetMapping("/api/test/business")
	void business() {
		throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS, Map.of("retryAfterSeconds", 42));
	}

	/** 세션을 만들어 SESSION 쿠키 속성을 확인하기 위한 엔드포인트(로그인 대용). */
	@GetMapping("/api/auth/test-session")
	ApiResponse<Void> createSession(HttpServletRequest request) {
		request.getSession().setAttribute("userId", 7L);
		return ApiResponse.success();
	}
}
