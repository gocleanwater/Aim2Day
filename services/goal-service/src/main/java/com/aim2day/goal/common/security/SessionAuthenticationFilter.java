package com.aim2day.goal.common.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 요청마다 Redis 세션의 userId(Long)를 읽어 인증 객체를 만든다(공유 계약 §4).
 * user-service가 로그인 때 세션에 userId를 넣어 두므로, 이 서비스는 그 값만 믿고 인증한다.
 *
 * - SPRING_SECURITY_CONTEXT를 세션에 저장·공유하지 않는다. 그 안의 principal 클래스가 서비스 간
 *   계약이 되면 한쪽만 바꿔도 역직렬화가 깨지기 때문이다.
 * - getSession(false)만 쓴다. goal-service는 세션을 읽기만 하고 만들지 않는다(로그인·로그아웃은 user-service).
 *   getSession()/getSession(true)를 쓰면 세션 없는 요청마다 빈 세션이 Redis에 쌓이고 SESSION 쿠키가 내려간다.
 * - principal은 Long userId 그대로다. 컨트롤러는 @AuthenticationPrincipal Long userId로 받는다.
 */
public class SessionAuthenticationFilter extends OncePerRequestFilter {

	/** user-service가 세션에 넣는 속성 이름(API 명세 §2.2). 값은 java.lang.Long이다. */
	static final String USER_ID_ATTRIBUTE = "userId";

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
			FilterChain filterChain) throws ServletException, IOException {
		HttpSession session = request.getSession(false);
		if (session != null && session.getAttribute(USER_ID_ATTRIBUTE) instanceof Long userId) {
			// Long이 아닌 값(다른 타입, null)은 인증으로 인정하지 않는다. 계약 위반 세션을 관대하게 받아주지 않는다.
			UsernamePasswordAuthenticationToken authentication = UsernamePasswordAuthenticationToken
					.authenticated(userId, null, AuthorityUtils.NO_AUTHORITIES);
			SecurityContext context = SecurityContextHolder.getContextHolderStrategy().createEmptyContext();
			context.setAuthentication(authentication);
			SecurityContextHolder.getContextHolderStrategy().setContext(context);
		}
		filterChain.doFilter(request, response);
	}
}
