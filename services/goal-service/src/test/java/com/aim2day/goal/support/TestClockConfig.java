package com.aim2day.goal.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** 운영 Clock(시스템 UTC)을 MutableClock으로 덮는다. 기본값은 2026-11-12T03:00:00Z(서울 12:00). */
@TestConfiguration
public class TestClockConfig {

	@Bean
	@Primary
	MutableClock mutableClock() {
		return new MutableClock();
	}
}
