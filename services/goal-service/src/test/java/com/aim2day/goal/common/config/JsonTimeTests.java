package com.aim2day.goal.common.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aim2day.goal.support.TestSessionConfig;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.TimeZone;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.session.MapSession;
import org.springframework.session.MapSessionRepository;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestSessionConfig.class)
class JsonTimeTests {

	@Autowired
	MockMvc mockMvc;
	@Autowired
	MapSessionRepository sessionRepository;

	@Test
	void jvmDefaultTimeZoneIsUtc() {
		assertThat(TimeZone.getDefault().getID()).isEqualTo("UTC");
	}

	@Test
	void timestampsAreSerializedAsIso8601Utc() throws Exception {
		MapSession session = sessionRepository.createSession();
		session.setAttribute("userId", 1L);
		sessionRepository.save(session);
		String sid = Base64.getEncoder().encodeToString(session.getId().getBytes(StandardCharsets.UTF_8));

		mockMvc.perform(get("/api/test/time").cookie(new Cookie("SESSION", sid)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.instant").value("2026-11-12T01:23:45Z"))
				.andExpect(jsonPath("$.date").value("2026-11-12T01:23:45.000Z"));
	}
}
