package com.aim2day.goal.common.security;

import com.aim2day.goal.common.exception.ErrorCode;
import com.aim2day.goal.common.response.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.csrf.CsrfException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Spring Security가 필터 단계에서 직접 내보내는 401/403을 공통 오류 JSON으로 만든다.
 * 필터에서 난 오류는 @RestControllerAdvice에 닿지 않고, 기본 설정은 빈 본문이나 HTML을 내보내
 * 프론트엔드가 code로 분기할 수 없기 때문이다(공유 계약 §5).
 */
public class SecurityErrorHandlers {

	private static final Logger log = LoggerFactory.getLogger(SecurityErrorHandlers.class);

	private final JsonMapper jsonMapper;

	public SecurityErrorHandlers(JsonMapper jsonMapper) {
		this.jsonMapper = jsonMapper;
	}

	/** 세션이 없거나 만료되어 인증되지 않은 요청 -> 401 UNAUTHENTICATED. */
	public AuthenticationEntryPoint authenticationEntryPoint() {
		return (HttpServletRequest request, HttpServletResponse response, AuthenticationException ex) ->
				write(response, ErrorCode.UNAUTHENTICATED);
	}

	/**
	 * CSRF 토큰 누락·불일치(CsrfException) -> 403 CSRF_TOKEN_INVALID.
	 * 그 외 접근 거부는 현재 규칙상 발생하지 않는다(인증 여부만 검사). 만약 생기면 명세에 맞는 코드가 없으므로
	 * 로그를 남기고 500으로 드러낸다. CSRF 오류로 위장하지 않는다.
	 */
	public AccessDeniedHandler accessDeniedHandler() {
		return (HttpServletRequest request, HttpServletResponse response, AccessDeniedException ex) -> {
			if (ex instanceof CsrfException) {
				write(response, ErrorCode.CSRF_TOKEN_INVALID);
			} else {
				log.warn("예상하지 못한 접근 거부", ex);
				write(response, ErrorCode.INTERNAL_ERROR);
			}
		};
	}

	private void write(HttpServletResponse response, ErrorCode code) throws IOException {
		response.setStatus(code.status().value());
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.setCharacterEncoding("UTF-8");
		response.getWriter().write(jsonMapper.writeValueAsString(ErrorResponse.of(code)));
	}
}
