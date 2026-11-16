package com.aim2day.goal.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.aim2day.goal.support.TestSessionConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

// 요청을 실제로 보내면 세션 필터가 저장소를 쓰므로, 공용 테스트 설정(메모리 세션 저장소)으로 외부 Redis 없이 실행한다.
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestSessionConfig.class)
class InstanceIdHeaderTests {

	@Autowired
	MockMvc mockMvc;

	@Autowired
	InstanceIdFilter instanceIdFilter;

	@Test
	void unauthenticatedResponseHasInstanceIdHeader() throws Exception {
		// 인증 없는 요청은 Security가 응답을 끝내지만 헤더는 붙어 있어야 한다.
		MvcResult result = mockMvc.perform(get("/api/goals")).andReturn();

		assertThat(result.getResponse().getStatus()).isEqualTo(401);
		// 비어 있지 않은 것만이 아니라 필터가 결정한 값과 정확히 같은지 비교한다.
		assertThat(result.getResponse().getHeader("X-Instance-Id")).isEqualTo(instanceIdFilter.instanceId());
	}

	@Test
	void csrfRejectedResponseHasInstanceIdHeader() throws Exception {
		// CSRF 필터가 403을 쓰고 끝내는 경우에도 헤더가 붙어야 한다.
		MvcResult result = mockMvc.perform(post("/api/goals")).andReturn();

		assertThat(result.getResponse().getStatus()).isEqualTo(403);
		assertThat(result.getResponse().getHeader("X-Instance-Id")).isEqualTo(instanceIdFilter.instanceId());
	}
}
