package com.aim2day.user.support;

import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.session.MapSessionRepository;
import org.springframework.session.data.redis.config.ConfigureRedisAction;

/**
 * 테스트용 메모리 세션 저장소. 요청을 보내면 세션 필터가 저장소에 접근하는데,
 * 기본 Redis 저장소는 외부 Redis가 없으면 실패한다. @Primary라서 SessionRepositoryFilter는 이 저장소를 쓴다.
 * 실제 인덱스형 Redis 저장소 빈은 그대로 남아 있어, 설정값 검증 테스트에서 읽을 수 있다.
 */
@TestConfiguration
public class TestSessionConfig {

	/**
	 * 기동 시 Redis에 notify-keyspace-events를 설정하는 초기화를 끈다. 이 초기화가 Redis에 바로 접속해
	 * 외부 Redis가 없으면 컨텍스트 기동 자체가 실패한다. 운영에서는 기본 동작(자동 설정)을 그대로 쓴다.
	 */
	@Bean
	static ConfigureRedisAction configureRedisAction() {
		return ConfigureRedisAction.NO_OP;
	}

	/**
	 * 세션 만료 이벤트를 구독하는 Redis 리스너 컨테이너가 기동 때 Redis에 접속하려다 실패하므로,
	 * 자동 시작만 끈다(빈 자체는 그대로 둔다).
	 */
	@Bean
	static BeanPostProcessor redisListenerAutoStartOff() {
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

	@Bean
	@Primary
	MapSessionRepository testSessionRepository() {
		return new MapSessionRepository(new ConcurrentHashMap<>());
	}
}
