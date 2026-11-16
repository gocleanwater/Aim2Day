package com.aim2day.goal.common.time;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 시각은 항상 이 Clock에서만 얻는다. 테스트가 고정 Clock으로 바꿔 끼울 수 있게 하기 위해서다. */
@Configuration
public class ClockConfig {

	@Bean
	Clock clock() {
		return Clock.systemUTC();
	}
}
