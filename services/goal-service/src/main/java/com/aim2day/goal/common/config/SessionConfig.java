package com.aim2day.goal.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.session.data.redis.config.annotation.web.http.EnableRedisIndexedHttpSession;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.DefaultCookieSerializer;

/**
 * Redis 세션 설정. user-service와 같은 세션 저장소·키 공간·쿠키여야 서로의 세션을 읽는다(공유 계약 §4).
 *
 * 어노테이션을 쓰면 Spring Boot의 spring.session.redis.* 속성 자동 설정이 물러나므로(저장소 빈이 이미 있음),
 * 만료 시간과 네임스페이스는 속성이 아니라 어노테이션 값으로 직접 지정한다.
 * 직렬화는 기본값(JDK 직렬화)을 바꾸지 않는다. 한쪽만 JSON으로 바꾸면 다른 쪽이 읽지 못한다.
 */
@Configuration
@EnableRedisIndexedHttpSession(
		maxInactiveIntervalInSeconds = 1800, // 마지막 요청 후 30분(API 명세 §2.2)
		redisNamespace = "spring:session")
public class SessionConfig {

	/**
	 * 세션 쿠키 설정(API 명세 §2.2): 이름 SESSION, HttpOnly, SameSite=Lax, Path=/.
	 * user-service와 값이 다르면 브라우저가 서로 다른 쿠키로 취급해 로그인이 풀린다.
	 * Secure는 요청이 HTTPS일 때 기본 규칙으로 붙게 둔다(현재 프록시는 HTTP 80).
	 * 그 밖의 속성(Base64 인코딩 등)은 기본값을 바꾸지 않는다. 두 서비스가 같은 기본값을 쓰기 때문이다.
	 */
	@Bean
	CookieSerializer cookieSerializer() {
		DefaultCookieSerializer serializer = new DefaultCookieSerializer();
		serializer.setCookieName("SESSION");
		serializer.setUseHttpOnlyCookie(true);
		serializer.setSameSite("Lax");
		// 기본값은 컨텍스트 경로라 우연히 "/"이지만, 두 서비스가 같은 쿠키를 공유해야 하므로 명시한다.
		serializer.setCookiePath("/");
		return serializer;
	}
}
