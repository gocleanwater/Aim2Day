package com.aim2day.user.common.session;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.session.FlushMode;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.DefaultCookieSerializer;
import org.springframework.session.data.redis.config.annotation.web.http.EnableRedisIndexedHttpSession;

/**
 * Redis 세션 설정. goal-service와 같은 값이어야 서로의 세션을 읽는다(공유 계약 §4).
 *
 * <p>인덱스형 저장소를 쓰는 이유: 비밀번호 재설정 때 사용자의 모든 세션을 찾아 지우려면
 * principal 이름(userId)으로 조회할 수 있어야 한다(API 명세 §2.2).
 *
 * <p>flushMode=IMMEDIATE인 이유: 기본(ON_SAVE)은 세션 변경을 응답이 커밋될 때 한꺼번에 Redis에 쓴다. 그러면 로그인이
 * "비밀번호 확인 -> (응답 커밋 때) 세션 저장" 사이에 비밀번호 재설정이 끝나고 그 사용자의 세션을 지워도, 삭제 목록에 없던
 * 세션이 뒤늦게 저장되어 살아남는다. IMMEDIATE는 setAttribute·invalidate 같은 변경을 호출 시점에 바로 Redis에 쓰므로,
 * 로그인이 세션을 저장한 뒤 해시를 다시 읽는 순서(AuthController.login)가 성립한다. 영향 범위: 이 서비스에서 세션을 바꾸는
 * 곳은 로그인(setAttribute)과 로그아웃·재설정(invalidate)뿐이고, 둘 다 원래 요청당 한두 번의 쓰기라 추가 비용은 로그인
 * 때 Redis 쓰기가 한 번 더 늘어나는 정도다. 세션을 읽기만 하는 요청은 저장하지 않는다. 필터가 응답 시점에 하는
 * 마지막 접근 시각 갱신은 그대로 커밋 때 저장된다.
 *
 * <p>값은 어노테이션 속성으로 직접 지정한다. 이 어노테이션을 쓰면 Boot의
 * spring.session.redis.* 속성이 적용되지 않을 수 있어, 코드에 둔 값이 실제로 쓰이도록 했다.
 * (application.properties의 값은 계약 문서에 맞춘 표기용으로 남겨 둔다.)
 */
@Configuration
@EnableRedisIndexedHttpSession(
		redisNamespace = SessionConfig.NAMESPACE,
		maxInactiveIntervalInSeconds = SessionConfig.MAX_INACTIVE_SECONDS,
		flushMode = FlushMode.IMMEDIATE)
public class SessionConfig {

	public static final String NAMESPACE = "spring:session";
	/** 마지막 요청 후 30분 동안 쓰지 않으면 만료(NFR-004). */
	public static final int MAX_INACTIVE_SECONDS = 30 * 60;
	public static final String COOKIE_NAME = "SESSION";

	/**
	 * 세션 쿠키 속성(API 명세 §2.2): HttpOnly(JS에서 못 읽음), SameSite=Lax(교차 사이트 요청 완화), Path=/.
	 * Secure는 명시하지 않는다. 기본 동작이 요청이 HTTPS일 때만 붙이는 것이라 HTTPS 배포 시 자동으로 붙는다.
	 * Base64 인코딩 등 나머지는 라이브러리 기본값을 유지한다. goal-service도 기본값이라 읽는 방식이 같다.
	 */
	@Bean
	CookieSerializer cookieSerializer() {
		DefaultCookieSerializer serializer = new DefaultCookieSerializer();
		serializer.setCookieName(COOKIE_NAME);
		serializer.setCookiePath("/");
		serializer.setUseHttpOnlyCookie(true);
		serializer.setSameSite("Lax");
		return serializer;
	}
}
