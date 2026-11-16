package com.aim2day.goal.common.exception;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aim2day.goal.support.TestSessionConfig;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestSessionConfig.class)
class ErrorHandlingTests {

	private static final String TOKEN = "csrf-test-token";

	@Autowired
	MockMvc mockMvc;
	@Autowired
	MapSessionRepository sessionRepository;

	/** 로그인 세션 + CSRF 쿠키/헤더를 갖춘 요청으로 만든다. 컨트롤러 단계의 오류만 확인하려는 것이다. */
	private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder builder) {
		MapSession session = sessionRepository.createSession();
		session.setAttribute("userId", 1L);
		sessionRepository.save(session);
		String sid = Base64.getEncoder().encodeToString(session.getId().getBytes(StandardCharsets.UTF_8));
		return builder.cookie(new Cookie("SESSION", sid), new Cookie("XSRF-TOKEN", TOKEN))
				.header("X-XSRF-TOKEN", TOKEN);
	}

	@Test
	void validationFailureReturns400WithFieldErrors() throws Exception {
		String body = "{\"title\":\"\",\"memo\":\"123456\",\"score\":11,\"digits\":\"abc\"}";
		mockMvc.perform(authed(post("/api/test/echo")).contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isBadRequest())
				.andExpect(header().exists("X-Instance-Id"))
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
				.andExpect(jsonPath("$.details.fieldErrors", hasSize(4)))
				.andExpect(jsonPath("$.details.fieldErrors[?(@.field=='title')].reason").value("REQUIRED"))
				.andExpect(jsonPath("$.details.fieldErrors[?(@.field=='memo')].reason").value("TOO_LONG"))
				.andExpect(jsonPath("$.details.fieldErrors[?(@.field=='score')].reason").value("OUT_OF_RANGE"))
				.andExpect(jsonPath("$.details.fieldErrors[?(@.field=='digits')].reason").value("INVALID_FORMAT"));
	}

	@Test
	void missingRequiredFieldIsReportedAsRequired() throws Exception {
		mockMvc.perform(authed(post("/api/test/echo")).contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.details.fieldErrors", hasSize(1)))
				.andExpect(jsonPath("$.details.fieldErrors[0].field").value("title"))
				.andExpect(jsonPath("$.details.fieldErrors[0].reason").value("REQUIRED"));
	}

	@Test
	void malformedJsonReturns400MalformedRequest() throws Exception {
		mockMvc.perform(authed(post("/api/test/echo")).contentType(MediaType.APPLICATION_JSON).content("{broken"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
				.andExpect(jsonPath("$.details").value(nullValue()));
	}

	@Test
	void businessExceptionUsesErrorCodeStatusAndDetails() throws Exception {
		mockMvc.perform(authed(get("/api/test/business")))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("GOAL_LIMIT_EXCEEDED"))
				.andExpect(jsonPath("$.details.year").value(2026))
				.andExpect(jsonPath("$.details.max").value(3));
	}

	@Test
	void unexpectedExceptionReturns500WithoutLeakingMessage() throws Exception {
		mockMvc.perform(authed(get("/api/test/boom")))
				.andExpect(status().isInternalServerError())
				.andExpect(header().exists("X-Instance-Id"))
				.andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
				.andExpect(jsonPath("$.details").value(nullValue()))
				.andExpect(content().string(not(containsString("민감한"))));
	}

	@Test
	void unknownPathReturns404ResourceNotFound() throws Exception {
		mockMvc.perform(authed(get("/api/test/no-such-path")))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
				.andExpect(jsonPath("$.details").value(nullValue()));
	}

	@Test
	void errorEndpointRendersCommonFormat() throws Exception {
		mockMvc.perform(get("/error").requestAttr("jakarta.servlet.error.status_code", 404))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
	}
}
