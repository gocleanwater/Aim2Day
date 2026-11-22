package com.aim2day.user.common.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import tools.jackson.databind.json.JsonMapper;

/**
 * Spring Security 설정. 이 서비스의 보안 정책(CSRF, 세션 기반 인증, 경로별 접근 규칙, JSON 오류 응답)을 한곳에서 정한다.
 * goal-service와 같은 Redis 세션·CSRF 쿠키 규칙을 공유하므로(공유 계약 §4) 이름·방식을 임의로 바꾸지 않는다.
 * 특히 sessionManagement 비활성화, RequestAttributeSecurityContextRepository, requestCache 비활성화 세 가지는
 * 빠지면 요청마다 세션 ID가 바뀌거나 세션 없는 요청이 세션을 만들므로 유지해야 한다.
 */
@Configuration
public class SecurityConfig {

	/** CSRF 쿠키 이름과 헤더 이름. goal-service와 같아야 한다(API 명세 §2.2). */
	public static final String CSRF_COOKIE_NAME = "XSRF-TOKEN";
	public static final String CSRF_HEADER_NAME = "X-XSRF-TOKEN";

	/**
	 * 필터 체인을 조립한다. 요청 흐름: CSRF 검사 -> (SessionUserIdAuthenticationFilter가 세션의 userId로 인증 생성)
	 * -> 경로별 인가. 인증 실패는 401, CSRF 실패는 403을 공통 오류 JSON(JsonSecurityErrorHandlers)으로 응답한다.
	 * 허용 경로: /api/auth/**, /docs/user/**, /error는 비로그인 허용이고 나머지 /api/**는 로그인 필요다.
	 */
	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http, JsonMapper jsonMapper) {
		JsonSecurityErrorHandlers errorHandlers = new JsonSecurityErrorHandlers(jsonMapper);

		// 쿠키 방식 토큰 저장소: 서버에 토큰을 저장하지 않아 goal-service 어느 인스턴스도 검증할 수 있다.
		// HttpOnly=false여야 프론트엔드 JS가 쿠키 값을 읽어 헤더에 넣을 수 있다.
		CookieCsrfTokenRepository csrfTokenRepository = CookieCsrfTokenRepository.withHttpOnlyFalse();
		csrfTokenRepository.setCookieName(CSRF_COOKIE_NAME);
		csrfTokenRepository.setHeaderName(CSRF_HEADER_NAME);
		// 기본값은 요청의 contextPath(빈 값이면 "/")지만, 두 서비스가 같은 쿠키를 쓰므로 명시적으로 고정한다.
		csrfTokenRepository.setCookiePath("/");

		http
			// SPA 방식: 쿠키 값을 그대로 헤더에 넣어 보내는 구조를 지원한다(Spring Security 7의 spa()).
			// 요청 파라미터로는 토큰을 받지 않고 헤더만 본다.
			// spa()는 내부에서 저장소를 withHttpOnlyFalse() 기본값으로 새로 지정하므로, spa()를 먼저 호출하고
			// 그 뒤에 우리 저장소를 지정해야 쿠키 이름·헤더·Path 설정이 덮어써지지 않는다.
			.csrf(csrf -> csrf.spa().csrfTokenRepository(csrfTokenRepository))
			// 세션은 로그인 컨트롤러와 SessionUserIdAuthenticationFilter가 직접 다룬다.
			// SPRING_SECURITY_CONTEXT를 세션에 저장하지 않도록 컨텍스트 저장소는 요청 속성(요청 한 번 동안만 유지)으로 둔다.
			.securityContext(sc -> sc.securityContextRepository(new RequestAttributeSecurityContextRepository()))
			// SessionManagementFilter를 아예 빼야 한다. 이 필터는 "저장소에 없는 인증 정보"를 방금 로그인한 것으로 보고
			// 세션 ID를 바꾼다(세션 고정 방어). 우리 필터가 만든 인증은 저장소에 저장되지 않으므로, 켜 두면
			// 요청마다 세션 ID가 바뀌어 쿠키가 계속 무효화되고 goal-service와의 동시 요청이 깨진다.
			// 로그인 시 세션 ID 재발급은 로그인 컨트롤러가 직접 한다(API 명세 §3.1).
			.sessionManagement(AbstractHttpConfigurer::disable)
			// 기본 로그인 폼, Basic 인증, 로그아웃 필터는 쓰지 않는다. 직접 만든 API로 처리한다.
			.formLogin(AbstractHttpConfigurer::disable)
			.httpBasic(AbstractHttpConfigurer::disable)
			.logout(AbstractHttpConfigurer::disable)
			// 같은 출처로만 쓰므로 CORS는 열지 않는다(API 명세 §2.2).
			.cors(AbstractHttpConfigurer::disable)
			.requestCache(cache -> cache.disable())
			.exceptionHandling(eh -> eh
					.authenticationEntryPoint(errorHandlers)
					.accessDeniedHandler(errorHandlers))
			.authorizeHttpRequests(auth -> auth
					// 인증 없이 허용: 로그인 전 흐름 전체(/api/auth/**), API 문서, Boot의 오류 디스패치(/error).
					.requestMatchers("/api/auth/**", "/docs/user/**", "/error").permitAll()
					// 나머지 /api/**는 세션의 userId가 있어야 한다.
					.requestMatchers("/api/**").authenticated()
					// 그 밖의 경로는 프록시가 이 서비스로 보내지 않는다. 있다면 존재를 드러내지 않도록 인증을 요구한다.
					.anyRequest().authenticated())
			.addFilterAfter(new SessionUserIdAuthenticationFilter(), SecurityContextHolderFilter.class);

		return http.build();
	}
}
