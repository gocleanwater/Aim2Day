package com.aim2day.goal.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.InetAddress;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 모든 응답에 X-Instance-Id(처리한 컨테이너의 호스트명) 헤더를 붙인다(DIST-003).
 * Nginx가 RR로 요청을 3개 컨테이너에 나누므로, 브라우저 개발자 도구에서 이 값이
 * 요청마다 바뀌는 것으로 분산을 시연한다.
 *
 * 최우선 순위(HIGHEST_PRECEDENCE)로 등록해 Spring Security 필터 체인보다 먼저 실행한다.
 * Security가 인증 실패 시 필터 체인 안에서 401/403을 직접 써서 응답을 끝내 버리므로,
 * 이 필터가 뒤에 있으면 그런 응답에는 헤더가 붙지 않는다.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class InstanceIdFilter extends OncePerRequestFilter {

	static final String HEADER_NAME = "X-Instance-Id";

	// 컨테이너는 --hostname을 컨테이너 이름과 같게 주고 실행된다(06 §1).
	// 호스트명은 기동 중 바뀌지 않으므로 시작 시 한 번만 구해 재사용한다(요청마다 조회하지 않음).
	private static final String INSTANCE_ID = resolveHostName();

	private static String resolveHostName() {
		try {
			return InetAddress.getLocalHost().getHostName();
		} catch (Exception e) {
			return "unknown";
		}
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
			FilterChain filterChain) throws ServletException, IOException {
		// 반드시 doFilter 호출 전에 설정한다. 호출 뒤에는 응답 본문이 이미 전송(커밋)되어
		// 헤더를 바꿔도 클라이언트에 전달되지 않을 수 있다.
		response.setHeader(HEADER_NAME, INSTANCE_ID);
		filterChain.doFilter(request, response);
	}
}
