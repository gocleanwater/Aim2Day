package com.aim2day.goal.support;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import com.aim2day.goal.common.exception.BusinessException;
import com.aim2day.goal.common.exception.ErrorCode;
import com.aim2day.goal.common.response.ApiResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** 공통 기반 검증 전용 컨트롤러. 테스트 소스에만 있고 운영 jar에는 들어가지 않는다. */
@RestController
public class TestApiController {

	public record SampleRequest(
			@NotBlank String title,
			@Size(max = 5) String memo,
			@Max(10) Integer score,
			@Pattern(regexp = "[0-9]+") String digits) {
	}

	@GetMapping("/api/test/whoami")
	ApiResponse<Map<String, Object>> whoami(@AuthenticationPrincipal Long userId) {
		return ApiResponse.success(Map.of("userId", userId));
	}

	@PostMapping("/api/test/echo")
	ApiResponse<Void> echo(@Valid @RequestBody SampleRequest request) {
		return ApiResponse.success();
	}

	@GetMapping("/api/test/business")
	void business() {
		throw new BusinessException(ErrorCode.GOAL_LIMIT_EXCEEDED, Map.of("year", 2026, "current", 3, "max", 3));
	}

	@GetMapping("/api/test/denied")
	void denied() {
		throw new org.springframework.security.access.AccessDeniedException("csrf 아님");
	}

	@GetMapping("/api/test/boom")
	void boom() {
		throw new IllegalStateException("민감한 내부 메시지");
	}

	@GetMapping("/api/test/time")
	Map<String, Object> time() {
		Instant instant = Instant.parse("2026-11-12T01:23:45Z");
		return Map.of(
				"instant", instant,
				"date", Date.from(instant));
	}
}
