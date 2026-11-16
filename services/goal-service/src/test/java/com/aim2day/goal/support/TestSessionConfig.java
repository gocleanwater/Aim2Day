package com.aim2day.goal.support;

import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.session.MapSession;
import org.springframework.session.MapSessionRepository;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.session.data.redis.config.ConfigureRedisAction;

/**
 * 테스트용 메모리 세션 저장소. 요청을 보내면 세션 필터가 저장소를 쓰는데, 운영 설정(Redis 인덱스형)은
 * 외부 Redis가 필요하므로 @Primary 메모리 저장소로 대체한다. Redis의 키 공간 알림 설정(CONFIG SET)도 끈다.
 */
@TestConfiguration
public class TestSessionConfig {

	@Bean
	ConcurrentHashMap<String, org.springframework.session.Session> testSessionStore() {
		return new ConcurrentHashMap<>();
	}

	@Bean
	@Primary
	MapSessionRepository testSessionRepository(ConcurrentHashMap<String, org.springframework.session.Session> store) {
		return new MapSessionRepository(store);
	}

	@Bean
	ConfigureRedisAction configureRedisAction() {
		return ConfigureRedisAction.NO_OP;
	}

	/**
	 * 인덱스형 Redis 세션 설정은 만료 이벤트를 받는 리스너 컨테이너를 기동 때 Redis에 연결한다.
	 * 테스트에는 Redis가 없으므로 이 컨테이너만 자동 시작하지 않게 한다.
	 */
	@Bean
	static BeanPostProcessor disableRedisListenerAutoStart() {
		return new BeanPostProcessor() {
			@Override
			public Object postProcessBeforeInitialization(Object bean, String beanName) {
				if (bean instanceof RedisMessageListenerContainer container) {
					container.setAutoStartup(false);
				}
				return bean;
			}
		};
	}
}
