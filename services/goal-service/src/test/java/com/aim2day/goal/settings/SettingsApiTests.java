package com.aim2day.goal.settings;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aim2day.goal.support.TestSessionConfig;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.session.MapSession;
import org.springframework.session.MapSessionRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest
@AutoConfigureMockMvc
@Import({TestSessionConfig.class, SettingsApiTests.FixedClockConfig.class})
class SettingsApiTests {

	private static final String TOKEN = "csrf-test-token";

	/** 2026-11-12T03:00Z = 서울 12:00. 날짜 계산 결과를 고정한다. */
	@TestConfiguration
	static class FixedClockConfig {
		@Bean
		@Primary
		Clock fixedClock() {
			return Clock.fixed(Instant.parse("2026-11-12T03:00:00Z"), ZoneOffset.UTC);
		}
	}

	@Autowired
	MockMvc mockMvc;
	@Autowired
	MapSessionRepository sessionRepository;
	@Autowired
	JdbcTemplate jdbc;

	@BeforeEach
	void reset() {
		jdbc.update("DELETE FROM user_settings");
		for (long id : new long[] {1L, 2L}) {
			jdbc.update("MERGE INTO users (id) KEY (id) VALUES (?)", id);
		}
	}

	private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder builder, long userId) {
		MapSession session = sessionRepository.createSession();
		session.setAttribute("userId", userId);
		sessionRepository.save(session);
		String sid = Base64.getEncoder().encodeToString(session.getId().getBytes(StandardCharsets.UTF_8));
		return builder.cookie(new Cookie("SESSION", sid), new Cookie("XSRF-TOKEN", TOKEN))
				.header("X-XSRF-TOKEN", TOKEN);
	}

	private MockHttpServletRequestBuilder putSettings(long userId, String json) {
		return authed(put("/api/settings"), userId).contentType(MediaType.APPLICATION_JSON).content(json);
	}

	@Test
	void firstGetCreatesDefaults() throws Exception {
		mockMvc.perform(authed(get("/api/settings"), 1L))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.weekStartDay").value("SUNDAY"))
				.andExpect(jsonPath("$.data.timezone").value("Asia/Seoul"))
				.andExpect(jsonPath("$.data.version").value(0))
				.andExpect(jsonPath("$.data.today").value("2026-11-12"))
				.andExpect(jsonPath("$.data.currentWeek.startDate").value("2026-11-08"))
				.andExpect(jsonPath("$.data.currentMonth.month").value("2026-11"))
				.andExpect(jsonPath("$.data.currentMonth.endDate").value("2026-11-30"));
	}

	@Test
	void putChangesWeekStartAndBumpsVersion() throws Exception {
		mockMvc.perform(putSettings(1L, "{\"weekStartDay\":\"MONDAY\",\"timezone\":\"Asia/Seoul\",\"version\":0}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.weekStartDay").value("MONDAY"))
				.andExpect(jsonPath("$.data.version").value(1))
				.andExpect(jsonPath("$.data.currentWeek.startDate").value("2026-11-09"));
	}

	@Test
	void staleVersionIs409WithCurrentVersion() throws Exception {
		String body = "{\"weekStartDay\":\"MONDAY\",\"timezone\":\"Asia/Seoul\",\"version\":0}";
		mockMvc.perform(putSettings(1L, body)).andExpect(status().isOk());
		mockMvc.perform(putSettings(1L, body))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("VERSION_CONFLICT"))
				.andExpect(jsonPath("$.details.currentVersion").value(1));
	}

	@Test
	void rejectsNonIanaTimezone() throws Exception {
		for (String zone : new String[] {"+09:00", "KST", "asia/seoul"}) {
			mockMvc.perform(putSettings(1L,
							"{\"weekStartDay\":\"MONDAY\",\"timezone\":\"" + zone + "\",\"version\":0}"))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
					.andExpect(jsonPath("$.details.fieldErrors[0].field").value("timezone"))
					.andExpect(jsonPath("$.details.fieldErrors[0].reason").value("INVALID_FORMAT"));
		}
	}

	@Test
	void unknownWeekStartDayIsMalformed() throws Exception {
		mockMvc.perform(putSettings(1L, "{\"weekStartDay\":\"FUNDAY\",\"timezone\":\"Asia/Seoul\",\"version\":0}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
	}

	@Test
	void missingVersionIs400() throws Exception {
		mockMvc.perform(putSettings(1L, "{\"weekStartDay\":\"MONDAY\",\"timezone\":\"Asia/Seoul\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
				.andExpect(jsonPath("$.details.fieldErrors[0].field").value("version"));
	}

	@Test
	void putWithoutCsrfIs403() throws Exception {
		MapSession session = sessionRepository.createSession();
		session.setAttribute("userId", 1L);
		sessionRepository.save(session);
		String sid = Base64.getEncoder().encodeToString(session.getId().getBytes(StandardCharsets.UTF_8));
		mockMvc.perform(put("/api/settings").cookie(new Cookie("SESSION", sid))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"weekStartDay\":\"MONDAY\",\"timezone\":\"Asia/Seoul\",\"version\":0}"))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("CSRF_TOKEN_INVALID"));
	}

	@Test
	void usersAreIsolated() throws Exception {
		mockMvc.perform(putSettings(1L, "{\"weekStartDay\":\"MONDAY\",\"timezone\":\"Asia/Seoul\",\"version\":0}"))
				.andExpect(status().isOk());
		mockMvc.perform(authed(get("/api/settings"), 2L))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.weekStartDay").value("SUNDAY"))
				.andExpect(jsonPath("$.data.version").value(0));
	}
}
