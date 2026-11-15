package com.aim2day.goal.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.InetAddress;
import java.util.concurrent.Callable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

	private static final Logger log = LoggerFactory.getLogger(InstanceIdFilter.class);

	static final String HEADER_NAME = "X-Instance-Id";

	// 호스트명은 기동 중 바뀌지 않으므로 시작 시 한 번만 구해 재사용한다(요청마다 조회하지 않음).
	// static 초기화 대신 생성자에서 구한다: 실패 시 ExceptionInInitializerError 대신
	// Spring 빈 생성 실패로 원인과 함께 기동 로그에 나오기 때문이다.
	private final String instanceId;

	public InstanceIdFilter() {
		String env = System.getenv("HOSTNAME");
		this.instanceId = resolveInstanceId(env, () -> InetAddress.getLocalHost().getHostName());
		String source = (env != null && !env.isBlank()) ? "HOSTNAME 환경 변수" : "InetAddress";
		log.info("X-Instance-Id 값 결정: {} (출처: {})", instanceId, source);
	}

	String instanceId() {
		return instanceId;
	}

	/**
	 * 1) HOSTNAME 환경 변수 -> 2) fallback 조회 순서로 호스트명을 정한다.
	 * 컨테이너는 --hostname을 컨테이너 이름과 같게 주고 실행되며(06 §1), Docker가 그 값을
	 * HOSTNAME 환경 변수로 넣어 주므로 DNS 조회 없이 얻을 수 있다.
	 * 둘 다 실패하면 "unknown" 같은 가짜 값으로 분산 시연을 망치지 않도록 기동을 실패시킨다.
	 */
	static String resolveInstanceId(String envHostname, Callable<String> fallbackLookup) {
		if (envHostname != null && !envHostname.isBlank()) {
			return envHostname.trim();
		}
		String fallback;
		try {
			fallback = fallbackLookup.call();
		} catch (Exception e) {
			throw new IllegalStateException("호스트명을 확인할 수 없어 X-Instance-Id를 설정하지 못했다", e);
		}
		if (fallback == null || fallback.isBlank()) {
			throw new IllegalStateException("호스트명이 비어 있어 X-Instance-Id를 설정하지 못했다");
		}
		return fallback.trim();
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
			FilterChain filterChain) throws ServletException, IOException {
		// 반드시 doFilter 호출 전에 설정한다. 호출 뒤에는 응답 본문이 이미 전송(커밋)되어
		// 헤더를 바꿔도 클라이언트에 전달되지 않을 수 있다.
		response.setHeader(HEADER_NAME, instanceId);
		filterChain.doFilter(request, response);
	}
}
