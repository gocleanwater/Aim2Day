package com.aim2day.user.session;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.data.redis.RedisIndexedSessionRepository;

/**
 * 실제 Redis에서 flushMode=IMMEDIATE가 동작하는지 증명한다(메모리 저장소로는 증명할 수 없다).
 *
 * <p>IT_REDIS_HOST 환경 변수가 있을 때만 실행한다. TestSessionConfig를 가져오지 않으므로 세션 저장소는 운영과 같은
 * SessionConfig의 RedisIndexedSessionRepository이고, Redis 접속 초기화도 Spring Session 기본 동작 그대로다.
 * 실행 예: IT_REDIS_HOST=aim2day-redis ./gradlew test --tests '*IT'
 */
@EnabledIfEnvironmentVariable(named = "IT_REDIS_HOST", matches = ".+")
@SpringBootTest(properties = "spring.data.redis.host=${IT_REDIS_HOST}")
class RedisSessionImmediateFlushIT {

	// 키 형식은 spring-session-data-redis 4.1.1의 RedisIndexedSessionRepository에서 확인했다:
	// 세션 = {namespace}sessions:{id}, 인덱스 = {namespace}index:{indexName}:{value} (namespace = "spring:session:").
	private static final String SESSION_KEY = "spring:session:sessions:";
	private static final String INDEX_KEY = "spring:session:index:"
			+ FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME + ":";

	@Autowired
	RedisIndexedSessionRepository sessionRepository;
	@Autowired
	StringRedisTemplate redis;

	@Test
	void setAttributeIsWrittenToRedisWithoutSave() {
		String principal = "it-immediate-" + System.nanoTime();
		RedisIndexedSessionRepository.RedisSession session = sessionRepository.createSession();
		try {
			session.setAttribute(FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME, principal);
			// save()를 부르지 않았다. ON_SAVE였다면 아래 키들은 아직 없어야 한다.

			assertThat(redis.hasKey(SESSION_KEY + session.getId())).isTrue();
			// 인덱스 set의 원소는 Spring Session 기본(JDK) 직렬화로 저장되므로 문자열로 읽으면 직렬화 머리 바이트가 앞에 붙는다.
			// 그래서 원소가 세션 ID로 끝나는지로 확인한다.
			assertThat(redis.opsForSet().members(INDEX_KEY + principal))
					.anySatisfy(member -> assertThat(member).endsWith(session.getId()));
			assertThat(sessionRepository.findByPrincipalName(principal)).containsKey(session.getId());
		}
		finally {
			sessionRepository.deleteById(session.getId());
		}
		// 정리 확인: deleteById는 해시를 바로 지우지 않고 만료(maxInactiveInterval=0)로 표시한 뒤 Redis 만료에 맡긴다.
		// 그래서 키 존재 여부가 아니라 "더 이상 세션으로 읽히지 않는지"와 인덱스에서 빠졌는지를 확인한다.
		assertThat(sessionRepository.findById(session.getId())).isNull();
		assertThat(sessionRepository.findByPrincipalName(principal)).isEmpty();
	}
}
