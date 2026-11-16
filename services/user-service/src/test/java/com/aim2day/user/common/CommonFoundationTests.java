package com.aim2day.user.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aim2day.user.support.TestSessionConfig;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.session.MapSession;
import org.springframework.session.MapSessionRepository;
import org.springframework.session.data.redis.RedisIndexedSessionRepository;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestSessionConfig.class)
class CommonFoundationTests {

	@Autowired
	MockMvc mockMvc;

	@Autowired
	MapSessionRepository sessionRepository;

	@Autowired
	RedisIndexedSessionRepository redisRepository;

	/** 메모리 저장소에 userId가 든 세션을 만들고, 브라우저가 보낼 SESSION 쿠키(Base64)를 돌려준다. */
	private Cookie sessionCookie(Object userId) {
		MapSession session = sessionRepository.createSession();
		session.setAttribute("userId", userId);
		sessionRepository.save(session);
		return new Cookie("SESSION",
				Base64.getEncoder().encodeToString(session.getId().getBytes(StandardCharsets.UTF_8)));
	}

	@Test
	void protectedPathWithoutSessionReturns401Json() throws Exception {
		mockMvc.perform(get("/api/test/whoami"))
				.andExpect(status().isUnauthorized())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
				.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
				.andExpect(jsonPath("$.message").isNotEmpty())
				.andExpect(jsonPath("$.details").doesNotExist())
				.andExpect(content().string(containsString("\"details\":null")));
	}

	@Test
	void postWithoutCsrfReturns403Json() throws Exception {
		mockMvc.perform(post("/api/test/echo").contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isForbidden())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
				.andExpect(jsonPath("$.code").value("CSRF_TOKEN_INVALID"))
				.andExpect(content().string(containsString("\"details\":null")));
	}

	@Test
	void csrfEndpointIssuesCookieWithRootPath() throws Exception {
		MvcResult result = mockMvc.perform(get("/api/auth/csrf"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.code").value("SUCCESS"))
				.andExpect(content().string(containsString("\"data\":null")))
				.andReturn();
		Cookie cookie = result.getResponse().getCookie("XSRF-TOKEN");
		assertThat(cookie).isNotNull();
		assertThat(cookie.getPath()).isEqualTo("/");
		assertThat(cookie.isHttpOnly()).isFalse();
		assertThat(cookie.getValue()).isNotBlank();
		// 비로그인 요청이 세션을 만들지 않는다.
		assertThat(result.getResponse().getCookie("SESSION")).isNull();
	}

	@Test
	void postWithMatchingCookieAndHeaderPassesCsrf() throws Exception {
		// 서버가 내려준 쿠키 값을 그대로 헤더에 넣는 SPA 흐름을 재현한다.
		String token = mockMvc.perform(get("/api/auth/csrf")).andReturn().getResponse()
				.getCookie("XSRF-TOKEN").getValue();
		mockMvc.perform(post("/api/auth/anything").cookie(new Cookie("XSRF-TOKEN", token))
				.header("X-XSRF-TOKEN", token))
				// CSRF는 통과하고 핸들러가 없어서 404가 된다(403이 아님).
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
	}

	@Test
	void mismatchedCsrfHeaderIsRejected() throws Exception {
		mockMvc.perform(post("/api/auth/anything").cookie(new Cookie("XSRF-TOKEN", "a"))
				.header("X-XSRF-TOKEN", "b"))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("CSRF_TOKEN_INVALID"));
	}

	@Test
	void sessionWithUserIdAuthenticates() throws Exception {
		mockMvc.perform(get("/api/test/whoami").cookie(sessionCookie(7L)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data").value(7));
	}

	@Test
	void sessionWithoutLongUserIdIsNotAuthenticated() throws Exception {
		mockMvc.perform(get("/api/test/whoami").cookie(sessionCookie("7")))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void authenticatedRequestDoesNotStoreSecurityContextInSession() throws Exception {
		Cookie cookie = sessionCookie(7L);
		mockMvc.perform(get("/api/test/whoami").cookie(cookie)).andExpect(status().isOk());
		String id = new String(Base64.getDecoder().decode(cookie.getValue()), StandardCharsets.UTF_8);
		// 세션 ID가 요청 중에 바뀌지 않아야 하고(SessionManagementFilter 회귀 방지), 속성은 userId 하나뿐이어야 한다.
		assertThat(sessionRepository.findById(id)).isNotNull();
		assertThat(sessionRepository.findById(id).getAttributeNames()).containsExactly("userId");
	}

	@Test
	void validationFailureReturns400WithFieldErrors() throws Exception {
		mockMvc.perform(post("/api/test/echo").cookie(sessionCookie(7L), new Cookie("XSRF-TOKEN", "t"))
				.header("X-XSRF-TOKEN", "t").contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":\"\",\"nick\":\"toolong\",\"pw\":\"ABC\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
				.andExpect(jsonPath("$.details.fieldErrors[?(@.field=='name')].reason").value("REQUIRED"))
				.andExpect(jsonPath("$.details.fieldErrors[?(@.field=='nick')].reason").value("TOO_LONG"))
				.andExpect(jsonPath("$.details.fieldErrors[?(@.field=='pw')].reason").value("PASSWORD_POLICY"));
	}

	@Test
	void sizeBelowMinimumIsOutOfRangeAndAboveMaximumIsTooLong() throws Exception {
		mockMvc.perform(post("/api/test/echo").cookie(sessionCookie(7L), new Cookie("XSRF-TOKEN", "t"))
				.header("X-XSRF-TOKEN", "t").contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":\"a\",\"tag\":\"x\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.details.fieldErrors[?(@.field=='tag')].reason").value("OUT_OF_RANGE"));
		mockMvc.perform(post("/api/test/echo").cookie(sessionCookie(7L), new Cookie("XSRF-TOKEN", "t"))
				.header("X-XSRF-TOKEN", "t").contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":\"a\",\"tag\":\"123456789\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.details.fieldErrors[?(@.field=='tag')].reason").value("TOO_LONG"));
	}

	@Test
	void missingRequiredHeaderKeeps400() throws Exception {
		mockMvc.perform(get("/api/test/header").cookie(sessionCookie(7L)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
				.andExpect(jsonPath("$.details").doesNotExist());
	}

	@Test
	void methodNotAllowedCopiesAllowHeader() throws Exception {
		mockMvc.perform(post("/api/test/whoami").cookie(sessionCookie(7L), new Cookie("XSRF-TOKEN", "t"))
				.header("X-XSRF-TOKEN", "t"))
				.andExpect(status().isMethodNotAllowed())
				.andExpect(header().string("Allow", containsString("GET")))
				.andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
	}

	@Test
	void errorDispatchWithClientStatusReturnsCommonJson() throws Exception {
		mockMvc.perform(get("/error").requestAttr("jakarta.servlet.error.status_code", 404)
				.requestAttr("jakarta.servlet.error.message", "secret-detail"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
				.andExpect(jsonPath("$.details").doesNotExist())
				.andExpect(content().string(not(containsString("secret-detail"))));
	}

	@Test
	void errorDispatchWithServerStatusReturns500Json() throws Exception {
		mockMvc.perform(get("/error").requestAttr("jakarta.servlet.error.status_code", 503)
				.requestAttr("jakarta.servlet.error.message", "secret-detail"))
				.andExpect(status().isInternalServerError())
				.andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
				.andExpect(content().string(not(containsString("secret-detail"))));
	}

	@Test
	void malformedJsonReturns400() throws Exception {
		mockMvc.perform(post("/api/test/echo").cookie(sessionCookie(7L), new Cookie("XSRF-TOKEN", "t"))
				.header("X-XSRF-TOKEN", "t").contentType(MediaType.APPLICATION_JSON).content("{bad"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
	}

	@Test
	void unexpectedExceptionReturns500WithoutLeakingDetail() throws Exception {
		mockMvc.perform(get("/api/test/boom").cookie(sessionCookie(7L)))
				.andExpect(status().isInternalServerError())
				.andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
				.andExpect(content().string(not(containsString("secret-detail"))));
	}

	@Test
	void businessExceptionUsesErrorCodeStatusAndDetails() throws Exception {
		mockMvc.perform(get("/api/test/business").cookie(sessionCookie(7L)))
				.andExpect(status().isTooManyRequests())
				.andExpect(jsonPath("$.code").value("TOO_MANY_REQUESTS"))
				.andExpect(jsonPath("$.details.retryAfterSeconds").value(42));
	}

	@Test
	void unknownPathReturns404Json() throws Exception {
		mockMvc.perform(get("/api/auth/nothing"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
	}

	@Test
	void dateTimesAreSerializedAsUtcIso() throws Exception {
		mockMvc.perform(get("/api/test/time").cookie(sessionCookie(7L)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.instant").value("2026-11-12T01:23:45Z"))
				.andExpect(jsonPath("$.data.offset").value("2026-11-12T01:23:45Z"))
				.andExpect(jsonPath("$.data.date").value("2026-11-12T01:23:45.000Z"));
	}

	@Test
	void redisSessionRepositoryUsesContractNamespaceAndThirtyMinutes() {
		assertThat(ReflectionTestUtils.getField(redisRepository, "namespace")).isEqualTo("spring:session:");
		assertThat(ReflectionTestUtils.getField(redisRepository, "defaultMaxInactiveInterval"))
				.isEqualTo(java.time.Duration.ofMinutes(30));
	}

	@Test
	void sessionCookieAttributesFollowContract() throws Exception {
		var headers = mockMvc.perform(get("/api/auth/test-session")).andExpect(status().isOk())
				.andReturn().getResponse().getHeaders("Set-Cookie");
		String setCookie = headers.stream().filter(h -> h.startsWith("SESSION=")).findFirst().orElseThrow();
		assertThat(setCookie).contains("Path=/").contains("HttpOnly").contains("SameSite=Lax");
	}
}
