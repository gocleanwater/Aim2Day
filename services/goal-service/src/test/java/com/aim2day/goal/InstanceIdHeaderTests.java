package com.aim2day.goal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.session.MapSessionRepository;
import org.springframework.session.SessionRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@Import(InstanceIdHeaderTests.InMemorySessionConfig.class)
class InstanceIdHeaderTests {

	// 요청을 실제로 보내면 세션 필터가 Redis에 접속하므로, 이 테스트에서만
	// 메모리 세션 저장소를 우선(@Primary) 사용해 외부 Redis 없이 실행한다.
	@TestConfiguration
	static class InMemorySessionConfig {
		@Bean
		@Primary
		SessionRepository<?> testSessionRepository() {
			return new MapSessionRepository(new ConcurrentHashMap<>());
		}
	}

	@Autowired
	MockMvc mockMvc;

	@Test
	void unauthenticatedResponseHasInstanceIdHeader() throws Exception {
		// 인증 없는 요청은 Security가 응답을 끝내지만 헤더는 붙어 있어야 한다.
		MvcResult result = mockMvc.perform(get("/api/goals")).andReturn();

		assertThat(result.getResponse().getStatus()).isIn(401, 403);
		assertThat(result.getResponse().getHeader("X-Instance-Id")).isNotBlank();
	}
}
