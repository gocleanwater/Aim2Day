package com.aim2day.user.common.security;

import com.aim2day.user.common.response.ErrorCode;
import com.aim2day.user.common.response.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.csrf.CsrfException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Spring Security 필터 단계의 실패를 공통 오류 JSON으로 내보낸다(공유 계약 §5).
 * 필터에서 끝나는 응답은 @RestControllerAdvice를 거치지 않으므로 직접 본문을 써야 한다.
 * 기본 설정을 두면 빈 본문이나 HTML이 나간다.
 */
public class JsonSecurityErrorHandlers implements AuthenticationEntryPoint, AccessDeniedHandler {

	private static final Logger log = LoggerFactory.getLogger(JsonSecurityErrorHandlers.class);

	private final JsonMapper jsonMapper;

	public JsonSecurityErrorHandlers(JsonMapper jsonMapper) {
		this.jsonMapper = jsonMapper;
	}

	/** 인증이 필요한데 세션이 없거나 userId가 없을 때: 401 UNAUTHENTICATED. */
	@Override
	public void commence(HttpServletRequest request, HttpServletResponse response,
			AuthenticationException authException) throws IOException {
		write(response, ErrorCode.UNAUTHENTICATED);
	}

	/**
	 * 접근 거부. CSRF 토큰 누락·불일치(CsrfException)는 403 CSRF_TOKEN_INVALID로 응답한다.
	 * 그 외 AccessDeniedException은 이 서비스에서 발생할 일이 없다(역할 기반 권한이 없고 익명 접근은
	 * commence로 간다). 명세에 일반 403 코드가 없고 인증 실패로 위장하면 원인이 가려지므로,
	 * 서버 버그로 보고 WARN 로그를 남긴 뒤 500 INTERNAL_ERROR로 응답한다(공유 계약 §5).
	 * 예외 메시지는 남기지 않는다(요청 정보가 섞일 수 있음).
	 */
	@Override
	public void handle(HttpServletRequest request, HttpServletResponse response,
			AccessDeniedException accessDeniedException) throws IOException {
		if (accessDeniedException instanceof CsrfException) {
			write(response, ErrorCode.CSRF_TOKEN_INVALID);
		}
		else {
			log.warn("예상치 못한 접근 거부({}): {} {}", accessDeniedException.getClass().getSimpleName(),
					request.getMethod(), request.getRequestURI());
			write(response, ErrorCode.INTERNAL_ERROR);
		}
	}

	private void write(HttpServletResponse response, ErrorCode code) throws IOException {
		response.setStatus(code.status().value());
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.setCharacterEncoding("UTF-8");
		jsonMapper.writeValue(response.getOutputStream(), ErrorResponse.of(code));
	}
}
