package com.aim2day.user.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.aim2day.user.auth.token.AuthTokenStore;
import com.aim2day.user.support.AuthTestConfig;
import com.aim2day.user.support.AuthTestConfig.InMemoryTokenStore;
import com.aim2day.user.support.AuthTestConfig.RecordingMailSender;
import com.aim2day.user.support.TestSessionConfig;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Spring MVC가 DEBUG/TRACE에서 요청·응답 객체를 toString으로 찍어도 비밀값이 로그에 남지 않는지 확인한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({ TestSessionConfig.class, AuthTestConfig.class })
@TestPropertySource(properties = "logging.level.org.springframework.web=TRACE")
@ExtendWith(OutputCaptureExtension.class)
class SensitiveLoggingTests {

	private static final String EMAIL = "leak.check@example.com";
	private static final String PASSWORD = "Zz9!leakcheckPW";
	private static final String TOKEN = "SECRET-TOKEN-ABCDEFG";

	@Autowired
	MockMvc mockMvc;
	@Autowired
	InMemoryTokenStore tokenStore;
	@Autowired
	RecordingMailSender mailSender;

	private String send(String path, String body) throws Exception {
		return mockMvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body)
				.cookie(new Cookie("XSRF-TOKEN", "t0ken")).header("X-XSRF-TOKEN", "t0ken")).andReturn().getResponse()
				.getContentAsString(StandardCharsets.UTF_8);
	}

	@Test
	void requestAndResponseObjectsDoNotLeakSecretsWhenWebLoggingIsTrace(CapturedOutput output) throws Exception {
		tokenStore.clear();
		mailSender.clear();
		send("/api/auth/email-codes", "{\"email\":\"" + EMAIL + "\"}");
		String code = mailSender.last().code();
		String verified = send("/api/auth/email-codes/verify", "{\"email\":\"" + EMAIL + "\",\"code\":\"" + code + "\"}");
		String signupToken = verified.replaceAll(".*\"signupToken\":\"([^\"]+)\".*", "$1");
		assertThat(signupToken).isNotEqualTo(verified);
		send("/api/auth/signup", "{\"signupToken\":\"" + signupToken + "\",\"username\":\"leakname\",\"password\":\""
				+ PASSWORD + "\",\"passwordConfirm\":\"" + PASSWORD + "\"}");
		send("/api/auth/login", "{\"email\":\"" + EMAIL + "\",\"password\":\"" + PASSWORD + "\"}");
		tokenStore.expireThrottle(AuthTokenStore.PURPOSE_RESET, EMAIL);
		send("/api/auth/password-reset-requests", "{\"email\":\"" + EMAIL + "\"}");
		send("/api/auth/password-reset", "{\"token\":\"" + TOKEN + "\",\"newPassword\":\"" + PASSWORD
				+ "\",\"newPasswordConfirm\":\"" + PASSWORD + "\"}");

		String log = output.getAll();
		// 로깅이 실제로 켜져 있었는지(요청 객체가 찍혔는지) 먼저 확인해 테스트가 헛돌지 않게 한다.
		assertThat(log).contains("PasswordResetRequest[token=***");
		assertThat(log).doesNotContain(EMAIL).doesNotContain(PASSWORD).doesNotContain(TOKEN)
				.doesNotContain(signupToken).doesNotContain("code=" + code).doesNotContain("leakname");
	}

	private static final String WEAK = "weakpw1";
	private static final String VALID_A = "Valid!Pass2026A";
	private static final String VALID_B = "Valid!Pass2026B";

	/** 검증 실패가 400이고, TRACE가 켜져 있음을 보이는 로그 줄(요청 매핑)이 있으면서 원문은 없어야 한다. */
	private void assertBadRequestWithoutLeak(String path, String handler, String body, CapturedOutput output, String... secrets)
			throws Exception {
		var response = mockMvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body)
				.cookie(new Cookie("XSRF-TOKEN", "t0ken")).header("X-XSRF-TOKEN", "t0ken")).andReturn().getResponse();
		assertThat(response.getStatus()).isEqualTo(400);
		String log = output.getAll();
		// TRACE 로깅이 실제로 켜져 있었는지: HandlerMapping이 TRACE로 남기는 "Mapped to ...Controller#메서드" 줄.
		assertThat(log).contains("TRACE").contains("Mapped to com.aim2day.user.auth.AuthController#" + handler);
		for (String secret : secrets) {
			assertThat(log).doesNotContain(secret);
		}
	}

	@Test
	void weakNewPasswordOnResetDoesNotLeak(CapturedOutput output) throws Exception {
		assertBadRequestWithoutLeak("/api/auth/password-reset", "resetPassword", "{\"token\":\"" + TOKEN + "\",\"newPassword\":\"" + WEAK
				+ "\",\"newPasswordConfirm\":\"" + WEAK + "\"}", output, WEAK, TOKEN);
	}

	@Test
	void mismatchedConfirmOnResetDoesNotLeak(CapturedOutput output) throws Exception {
		assertBadRequestWithoutLeak("/api/auth/password-reset", "resetPassword", "{\"token\":\"" + TOKEN + "\",\"newPassword\":\""
				+ VALID_A + "\",\"newPasswordConfirm\":\"" + VALID_B + "\"}", output, VALID_A, VALID_B, TOKEN);
	}

	@Test
	void weakPasswordOnSignupDoesNotLeak(CapturedOutput output) throws Exception {
		assertBadRequestWithoutLeak("/api/auth/signup", "signup", "{\"signupToken\":\"" + TOKEN + "\",\"username\":\"leakname\","
				+ "\"password\":\"" + WEAK + "\",\"passwordConfirm\":\"" + WEAK + "\"}", output, WEAK, TOKEN);
	}
}
