package com.aim2day.goal.common.security;

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
 * goal-service 보안 설정. 인증은 user-service가 만든 Redis 세션의 userId로 판단하고(공유 계약 §4),
 * 상태 변경 요청은 CSRF 토큰으로 막는다(API 명세 §2.2, 결정 13).
 */
@Configuration
public class SecurityConfig {

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http, JsonMapper jsonMapper) {
		SecurityErrorHandlers errorHandlers = new SecurityErrorHandlers(jsonMapper);

		http
			// 로그인 폼·Basic·로그아웃은 쓰지 않는다. 로그인/로그아웃은 user-service의 API가 한다.
			.formLogin(form -> form.disable())
			.httpBasic(basic -> basic.disable())
			.logout(logout -> logout.disable())

			// SecurityContext는 요청 한 번 동안만 요청 속성에 둔다. SPRING_SECURITY_CONTEXT를 세션에 저장하지 않는다.
			.securityContext(sc -> sc.securityContextRepository(new RequestAttributeSecurityContextRepository()))
			// SessionManagementFilter를 뺀다. 이 필터는 "저장소에 없는 인증"을 방금 로그인한 것으로 보고
			// 세션 ID를 바꾼다(세션 고정 방어). 우리 필터의 인증은 저장되지 않으므로, 켜 두면 요청마다
			// 세션 ID가 바뀌어 SESSION 쿠키가 무효화되고 다른 인스턴스로 가는 동시 요청이 깨진다.
			.sessionManagement(AbstractHttpConfigurer::disable)
			// 인증 실패 시 원래 요청을 세션에 저장하는 RequestCache를 끈다. 켜 두면 세션 없는 요청이
			// 새 세션(SESSION 쿠키)을 만든다. 세션은 user-service만 만든다.
			.requestCache(cache -> cache.disable())

			// 쿠키(XSRF-TOKEN)에 토큰을 두므로 서버는 상태를 저장하지 않는다. RR로 어느 인스턴스가 받아도,
			// user-service가 발급한 쿠키 값이어도 "쿠키 값 == X-XSRF-TOKEN 헤더 값"만 비교하면 되기 때문이다.
			// withHttpOnlyFalse: 프론트 JS가 쿠키를 읽어 헤더에 넣어야 하므로 HttpOnly를 끈다.
			// spa(): 쿠키 값을 헤더에 그대로 넣는 SPA 방식용 처리기. 기본 처리기는 요청마다 값을 가린(마스킹)
			// 토큰을 기대해, 쿠키 값을 그대로 보내면 403이 난다. user-service도 같은 설정을 써야 한다.
			// 순서 주의: spa()가 내부에서 저장소를 기본 withHttpOnlyFalse()로 덮어쓰므로 spa()를 먼저 부르고
			// 그 뒤에 이름·Path를 명시한 저장소를 지정한다. 반대로 하면 아래 설정이 사라진다.
			.csrf(csrf -> csrf
					.spa()
					.csrfTokenRepository(csrfTokenRepository()))

			.authorizeHttpRequests(auth -> auth
					// Swagger 문서와 /error는 인증 없이 허용한다(공유 계약 §7). /error를 열지 않으면
					// 서블릿 오류 전달(error dispatch) 때 다시 401이 나서 원래 오류 본문이 가려진다.
					.requestMatchers("/docs/goal/**", "/error").permitAll()
					.anyRequest().authenticated())

			// 필터에서 난 401/403도 공통 오류 JSON으로 내보낸다.
			.exceptionHandling(ex -> ex
					.authenticationEntryPoint(errorHandlers.authenticationEntryPoint())
					.accessDeniedHandler(errorHandlers.accessDeniedHandler()))

			// 인증 객체를 만드는 필터. SecurityContextHolderFilter가 빈 컨텍스트를 준비한 직후에 두어
			// 그 뒤의 인가 검사가 우리가 만든 인증 객체를 보게 한다.
			.addFilterAfter(new SessionAuthenticationFilter(), SecurityContextHolderFilter.class);

		return http.build();
	}

	/** user-service와 같은 쿠키 이름·헤더 이름·Path(API 명세 §2.2). 어긋나면 쿠키를 서로 못 읽는다. */
	private static CookieCsrfTokenRepository csrfTokenRepository() {
		CookieCsrfTokenRepository repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
		repository.setCookieName("XSRF-TOKEN");
		repository.setHeaderName("X-XSRF-TOKEN");
		// 기본값은 요청 contextPath라 환경에 따라 달라질 수 있어 "/"로 고정한다.
		repository.setCookiePath("/");
		return repository;
	}
}
