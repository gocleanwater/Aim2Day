package com.aim2day.goal.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aim2day.goal.support.TestSessionConfig;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.session.MapSession;
import org.springframework.session.MapSessionRepository;
import org.springframework.session.Session;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestSessionConfig.class)
class SecurityTests {

	@Autowired
	MockMvc mockMvc;
	@Autowired
	MapSessionRepository sessionRepository;
	@Autowired
	ConcurrentHashMap<String, Session> sessionStore;

	@BeforeEach
	void clear() {
		sessionStore.clear();
	}

	/** user-service가 로그인한 것처럼 세션에 userId를 넣고, 그 세션을 가리키는 SESSION 쿠키를 만든다. */
	private Cookie loginCookie(Object userId) {
		MapSession session = sessionRepository.createSession();
		session.setAttribute("userId", userId);
		sessionRepository.save(session);
		// 세션 쿠키 값은 세션 ID의 Base64 인코딩이다(Spring Session 기본 쿠키 직렬화).
		return new Cookie("SESSION", Base64.getEncoder().encodeToString(session.getId().getBytes(StandardCharsets.UTF_8)));
	}

	@Test
	void unauthenticatedRequestGets401WithErrorBodyAndInstanceId() throws Exception {
		mockMvc.perform(get("/api/test/whoami"))
				.andExpect(status().isUnauthorized())
				.andExpect(header().exists("X-Instance-Id"))
				.andExpect(header().string("Content-Type", containsString("application/json")))
				.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
				.andExpect(jsonPath("$.message").isNotEmpty())
				.andExpect(jsonPath("$.details").value(nullValue()));
	}

	@Test
	void postWithoutCsrfGets403CsrfTokenInvalid() throws Exception {
		mockMvc.perform(post("/api/test/echo").contentType(MediaType.APPLICATION_JSON).content("{}")
						.cookie(loginCookie(1L)))
				.andExpect(status().isForbidden())
				.andExpect(header().exists("X-Instance-Id"))
				.andExpect(jsonPath("$.code").value("CSRF_TOKEN_INVALID"))
				.andExpect(jsonPath("$.details").value(nullValue()));
	}

	@Test
	void mismatchedCsrfCookieAndHeaderGets403() throws Exception {
		mockMvc.perform(post("/api/test/echo").contentType(MediaType.APPLICATION_JSON).content("{}")
						.cookie(loginCookie(1L), new Cookie("XSRF-TOKEN", "abc"))
						.header("X-XSRF-TOKEN", "different"))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("CSRF_TOKEN_INVALID"));
	}

	@Test
	void matchingCsrfCookieAndHeaderPassesCsrf() throws Exception {
		// user-service가 발급한 쿠키 값을 흉내 낸 임의 값. 쿠키와 헤더가 같으면 서버 상태 없이 통과해야 한다.
		String token = "6f1c2b9e-1d3a-4e0b-9a77-aaaaaaaaaaaa";
		mockMvc.perform(post("/api/test/echo").contentType(MediaType.APPLICATION_JSON)
						.content("{\"title\":\"ok\"}")
						.cookie(loginCookie(1L), new Cookie("XSRF-TOKEN", token))
						.header("X-XSRF-TOKEN", token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.code").value("SUCCESS"))
				.andExpect(jsonPath("$.data").value(nullValue()));
	}

	@Test
	void validCsrfButNoSessionGets401() throws Exception {
		mockMvc.perform(post("/api/test/echo").contentType(MediaType.APPLICATION_JSON).content("{}")
						.cookie(new Cookie("XSRF-TOKEN", "t"))
						.header("X-XSRF-TOKEN", "t"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
	}

	@Test
	void sessionWithUserIdAuthenticates() throws Exception {
		mockMvc.perform(get("/api/test/whoami").cookie(loginCookie(42L)))
				.andExpect(status().isOk())
				.andExpect(header().exists("X-Instance-Id"))
				.andExpect(jsonPath("$.data.userId").value(42));
	}

	@Test
	void authenticatedRequestKeepsSessionId() throws Exception {
		// 인증된 요청마다 세션 ID가 바뀌면(세션 고정 방어 오작동) SESSION 쿠키가 계속 무효화된다.
		Cookie sessionCookie = loginCookie(7L);
		String before = sessionStore.keySet().iterator().next();
		mockMvc.perform(get("/api/test/whoami").cookie(sessionCookie))
				.andExpect(status().isOk())
				.andExpect(cookie().doesNotExist("SESSION"));
		assertThat(sessionStore.keySet()).containsExactly(before);
	}

	@Test
	void nonCsrfAccessDeniedGets500InternalError() throws Exception {
		mockMvc.perform(get("/api/test/denied").cookie(loginCookie(1L)))
				.andExpect(status().isInternalServerError())
				.andExpect(header().exists("X-Instance-Id"))
				.andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
	}

	@Test
	void methodNotAllowedKeepsAllowHeaderAndErrorBody() throws Exception {
		String token = "t-405";
		mockMvc.perform(post("/api/test/whoami")
						.cookie(loginCookie(1L), new Cookie("XSRF-TOKEN", token))
						.header("X-XSRF-TOKEN", token))
				.andExpect(status().isMethodNotAllowed())
				.andExpect(header().string("Allow", containsString("GET")))
				.andExpect(header().exists("X-Instance-Id"))
				.andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
	}

	@Test
	void sessionWithNonLongUserIdIsNotAuthenticated() throws Exception {
		mockMvc.perform(get("/api/test/whoami").cookie(loginCookie(42)))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void unknownSessionCookieGets401() throws Exception {
		mockMvc.perform(get("/api/test/whoami").cookie(new Cookie("SESSION", "bm9wZQ==")))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void requestWithoutSessionDoesNotCreateSession() throws Exception {
		MvcResult result = mockMvc.perform(get("/api/test/whoami")).andReturn();

		List<String> setCookies = result.getResponse().getHeaders("Set-Cookie");
		assertThat(setCookies).noneMatch(c -> c.startsWith("SESSION="));
		assertThat(sessionStore).isEmpty();
	}

	@Test
	void docsAndErrorPathsNeedNoAuthentication() throws Exception {
		mockMvc.perform(get("/docs/goal/v3/api-docs")).andExpect(status().isOk());
		mockMvc.perform(get("/error")).andExpect(status().isInternalServerError());
	}
}
