package com.aim2day.user.common.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 요청마다 Redis 세션의 userId 속성(java.lang.Long)을 읽어 인증 객체를 만든다(공유 계약 §4).
 *
 * <p>Spring Security가 세션에 SPRING_SECURITY_CONTEXT를 저장하는 기본 방식을 쓰지 않는 이유:
 * 그 안의 principal 타입이 서비스 간 계약이 되어, goal-service가 역직렬화하지 못하면 깨진다.
 * 그래서 두 서비스 모두 "userId 하나"만 믿고 요청 때마다 인증 객체를 새로 만든다.
 */
public class SessionUserIdAuthenticationFilter extends OncePerRequestFilter {

	/** 로그인 때 세션에 저장하는 속성 이름. goal-service와 반드시 같아야 한다. */
	public static final String USER_ID_ATTRIBUTE = "userId";

	private final SecurityContextHolderStrategy securityContextHolderStrategy =
			SecurityContextHolder.getContextHolderStrategy();

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		// getSession(false): 세션이 없으면 만들지 않는다. 비로그인 요청마다 Redis에 빈 세션이 쌓이는 것을 막는다.
		HttpSession session = request.getSession(false);
		if (session != null && session.getAttribute(USER_ID_ATTRIBUTE) instanceof Long userId) {
			// 권한(role) 개념이 없으므로 authorities는 비운다. principal에는 JDK 표준 타입 Long만 쓴다.
			var authentication = UsernamePasswordAuthenticationToken.authenticated(userId, null, List.of());
			SecurityContext context = securityContextHolderStrategy.createEmptyContext();
			context.setAuthentication(authentication);
			securityContextHolderStrategy.setContext(context);
		}
		chain.doFilter(request, response);
	}
}
