package com.aim2day.user.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aim2day.user.auth.token.AuthTokenStore;
import com.aim2day.user.auth.token.TokenCodec;
import com.aim2day.user.support.AuthTestConfig;
import com.aim2day.user.support.AuthTestConfig.InMemoryTokenStore;
import com.aim2day.user.support.AuthTestConfig.RecordingMailSender;
import com.aim2day.user.support.TestSessionConfig;
import com.aim2day.user.support.TestSessionConfig.IndexedMapSessionRepository;
import com.aim2day.user.user.User;
import com.aim2day.user.user.UserRepository;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/** 비밀번호 재설정(링크 요청 + 새 비밀번호 제출). 저장소·메일·세션은 테스트용 메모리 구현이다. */
@SpringBootTest
@AutoConfigureMockMvc
@Import({ TestSessionConfig.class, AuthTestConfig.class })
@ExtendWith(OutputCaptureExtension.class)
class PasswordResetFlowTests {

	private static final String EMAIL = "user@example.com";
	private static final String OTHER_EMAIL = "other@example.com";
	private static final String OLD_PASSWORD = "Aim2day!2026";
	private static final String NEW_PASSWORD = "NewPass!2026";
	private static final String LINK_PREFIX = "http://localhost/reset-password.html#token=";

	@Autowired
	MockMvc mockMvc;
	@Autowired
	UserRepository userRepository;
	@Autowired
	PasswordEncoder passwordEncoder;
	@Autowired
	InMemoryTokenStore tokenStore;
	@Autowired
	RecordingMailSender mailSender;
	@Autowired
	IndexedMapSessionRepository sessionRepository;

	@BeforeEach
	void reset() {
		userRepository.deleteAll();
		tokenStore.clear();
		mailSender.clear();
		userRepository.saveAndFlush(new User(EMAIL, "정수", passwordEncoder.encode(OLD_PASSWORD)));
		userRepository.saveAndFlush(new User(OTHER_EMAIL, "다른사람", passwordEncoder.encode(OLD_PASSWORD)));
	}

	// ---- 요청 도우미 ----

	private ResultActions postJson(String path, String body) throws Exception {
		return mockMvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body)
				.cookie(new Cookie("XSRF-TOKEN", "t0ken")).header("X-XSRF-TOKEN", "t0ken"));
	}

	private ResultActions requestReset(String email) throws Exception {
		return postJson("/api/auth/password-reset-requests", "{\"email\":\"" + email + "\"}");
	}

	private ResultActions submit(String token, String password, String confirm) throws Exception {
		return postJson("/api/auth/password-reset", "{\"token\":\"" + token + "\",\"newPassword\":\"" + password
				+ "\",\"newPasswordConfirm\":\"" + confirm + "\"}");
	}

	private ResultActions login(String email, String password) throws Exception {
		return postJson("/api/auth/login", "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}");
	}

	private static String tokenOf(String link) {
		assertThat(link).startsWith(LINK_PREFIX);
		return link.substring(LINK_PREFIX.length());
	}

	/** 요청 -> 메일에서 토큰 꺼내기. 이전 요청의 제한은 풀어(60초 경과 흉내) 연속 발급이 가능하게 한다. */
	private String issueToken(String email) throws Exception {
		tokenStore.expireThrottle(AuthTokenStore.PURPOSE_RESET, email);
		requestReset(email).andExpect(status().isOk());
		return tokenOf(mailSender.lastReset().link());
	}

	private Long userId(String email) {
		return userRepository.findByEmail(email).orElseThrow().getId();
	}

	private String loginAndGetSessionId(String email) throws Exception {
		MvcResult r = login(email, OLD_PASSWORD).andExpect(status().isOk()).andReturn();
		String cookie = r.getResponse().getHeaders(HttpHeaders.SET_COOKIE).stream()
				.filter(h -> h.startsWith("SESSION=")).findFirst().orElseThrow();
		String value = cookie.substring("SESSION=".length()).split(";", 2)[0];
		return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
	}

	// ---- POST /api/auth/password-reset-requests ----

	@Test
	void registeredEmailGetsOneMailWithResetLinkAndStoredTokenIsHashed() throws Exception {
		requestReset(EMAIL).andExpect(status().isOk())
				.andExpect(jsonPath("$.code").value("SUCCESS"))
				.andExpect(jsonPath("$.data").doesNotExist());

		assertThat(mailSender.sentReset()).hasSize(1);
		assertThat(mailSender.lastReset().to()).isEqualTo(EMAIL);
		String token = tokenOf(mailSender.lastReset().link());
		assertThat(token).matches("[A-Za-z0-9_-]{43}");
		assertThat(tokenStore.hasResetToken(token)).isTrue();
		assertThat(tokenStore.containsRawResetToken(token)).isFalse();
		assertThat(tokenStore.isThrottled(AuthTokenStore.PURPOSE_RESET, EMAIL)).isTrue();
		// 재설정 제한은 가입 인증번호 제한과 따로 센다.
		assertThat(tokenStore.isThrottled(AuthTokenStore.PURPOSE_SIGNUP, EMAIL)).isFalse();
	}

	@Test
	void emailIsNormalizedBeforeLookup() throws Exception {
		requestReset("  User@Example.COM ").andExpect(status().isOk());
		assertThat(mailSender.sentReset()).hasSize(1);
		assertThat(mailSender.lastReset().to()).isEqualTo(EMAIL);
	}

	@Test
	void unknownEmailGets200WithIdenticalBodyAndNoMail() throws Exception {
		String known = requestReset(EMAIL).andExpect(status().isOk()).andReturn().getResponse()
				.getContentAsString(StandardCharsets.UTF_8);
		mailSender.clear();
		String unknown = requestReset("nobody@example.com").andExpect(status().isOk()).andReturn().getResponse()
				.getContentAsString(StandardCharsets.UTF_8);

		assertThat(unknown).isEqualTo(known);
		assertThat(mailSender.sentReset()).isEmpty();
		assertThat(tokenStore.resetTokenCount()).isEqualTo(1); // 가입 계정의 토큰 하나뿐
		// 가입 여부와 관계없이 제한이 걸린다(응답으로 구별할 수 없게).
		assertThat(tokenStore.isThrottled(AuthTokenStore.PURPOSE_RESET, "nobody@example.com")).isTrue();
	}

	@Test
	void secondRequestWithin60SecondsIs429ForRegisteredAndUnknownAlike() throws Exception {
		for (String email : List.of(EMAIL, "nobody@example.com")) {
			requestReset(email).andExpect(status().isOk());
			requestReset(email).andExpect(status().isTooManyRequests())
					.andExpect(jsonPath("$.code").value("TOO_MANY_REQUESTS"))
					.andExpect(jsonPath("$.details.retryAfterSeconds").value(AuthTokenStore.THROTTLE_SECONDS));
		}
		assertThat(mailSender.sentReset()).hasSize(1); // 429 요청은 메일을 보내지 않는다.
	}

	@Test
	void invalidEmailIs400AndDoesNotConsumeThrottle() throws Exception {
		requestReset("not-an-email").andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
				.andExpect(jsonPath("$.details.fieldErrors[0].field").value("email"));
		postJson("/api/auth/password-reset-requests", "{}").andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.details.fieldErrors[0].reason").value("REQUIRED"));
		assertThat(tokenStore.isThrottled(AuthTokenStore.PURPOSE_RESET, "not-an-email")).isFalse();
	}

	@Test
	void requestsRequireCsrfToken() throws Exception {
		mockMvc.perform(post("/api/auth/password-reset-requests").contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"" + EMAIL + "\"}")).andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("CSRF_TOKEN_INVALID"));
		mockMvc.perform(post("/api/auth/password-reset").contentType(MediaType.APPLICATION_JSON)
				.content("{\"token\":\"x\",\"newPassword\":\"" + NEW_PASSWORD + "\",\"newPasswordConfirm\":\""
						+ NEW_PASSWORD + "\"}"))
				.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CSRF_TOKEN_INVALID"));
	}

	@Test
	void mailFailureStill200KeepsThrottleRemovesTokenAndLogsOnlyUserIdAndClass(CapturedOutput output) throws Exception {
		mailSender.failNext(true);

		requestReset(EMAIL).andExpect(status().isOk()).andExpect(jsonPath("$.data").doesNotExist());

		// 제한은 풀지 않는다: 바로 다시 요청하면 429.
		assertThat(tokenStore.isThrottled(AuthTokenStore.PURPOSE_RESET, EMAIL)).isTrue();
		requestReset(EMAIL).andExpect(status().isTooManyRequests());
		// 받을 수 없는 링크의 토큰은 남지 않는다.
		assertThat(tokenStore.resetTokenCount()).isZero();
		assertThat(tokenStore.hasLatestResetMarker(userId(EMAIL))).isFalse();
		assertThat(output.getAll()).contains("비밀번호 재설정 메일 전송 실패 userId=" + userId(EMAIL))
				.contains("cause=MailDeliveryException")
				.doesNotContain(EMAIL).doesNotContain("boom");
	}

	@Test
	void releaseOnlyRemovesTokenWhileItIsStillTheLatest() {
		InMemoryTokenStore store = tokenStore;
		store.acquireMailThrottle(AuthTokenStore.PURPOSE_RESET, "x@example.com", "owner");
		store.issueResetToken(7L, "first", "x@example.com", "owner");
		store.issueResetToken(7L, "second", "x@example.com", "owner");

		// 오래된 요청이 뒤늦게 실패해도 더 새 토큰은 건드리지 않는다.
		store.releaseResetTokenIfLatest(7L, "first");
		assertThat(store.hasResetToken("second")).isTrue();
		assertThat(store.hasLatestResetMarker(7L)).isTrue();

		store.releaseResetTokenIfLatest(7L, "second");
		assertThat(store.hasResetToken("second")).isFalse();
		assertThat(store.hasLatestResetMarker(7L)).isFalse();
	}

	@Test
	void newTokenInvalidatesPreviousLinkAndOnlyLastWorks() throws Exception {
		String first = issueToken(EMAIL);
		String second = issueToken(EMAIL);
		assertThat(second).isNotEqualTo(first);
		assertThat(tokenStore.resetTokenCount()).isEqualTo(1);

		submit(first, NEW_PASSWORD, NEW_PASSWORD).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("AUTH_INVALID_TOKEN"));
		submit(second, NEW_PASSWORD, NEW_PASSWORD).andExpect(status().isOk());
	}

	@Test
	void concurrentIssuesLeaveExactlyOneValidToken() throws Exception {
		int threads = 16;
		tokenStore.acquireMailThrottle(AuthTokenStore.PURPOSE_RESET, "x@example.com", "owner");
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		CountDownLatch start = new CountDownLatch(1);
		List<String> tokens = new ArrayList<>();
		List<Future<?>> futures = new ArrayList<>();
		for (int i = 0; i < threads; i++) {
			String token = TokenCodec.newToken();
			tokens.add(token);
			futures.add(pool.submit(() -> {
				start.await();
				tokenStore.issueResetToken(42L, token, "x@example.com", "owner");
				return null;
			}));
		}
		start.countDown();
		for (Future<?> f : futures) {
			f.get(10, TimeUnit.SECONDS);
		}
		pool.shutdown();

		assertThat(tokenStore.resetTokenCount()).isEqualTo(1);
		assertThat(tokens.stream().filter(tokenStore::hasResetToken)).hasSize(1);
	}

	@Test
	void staleRequestThatLostThrottleOwnershipDoesNotInvalidateNewerLinkAndSendsNoMail() throws Exception {
		CountDownLatch reached = new CountDownLatch(1);
		CountDownLatch proceed = new CountDownLatch(1);
		AtomicBoolean first = new AtomicBoolean();
		// 첫 발급 호출(요청 A)만 제한 획득 뒤, 저장 전에 멈춘다. 이후 호출(B)은 그대로 통과한다.
		tokenStore.beforeIssueHook = () -> {
			if (first.compareAndSet(false, true)) {
				reached.countDown();
				try {
					proceed.await(10, TimeUnit.SECONDS);
				}
				catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
			}
		};
		ExecutorService pool = Executors.newSingleThreadExecutor();
		try {
			Future<MvcResult> a = pool.submit(() -> requestReset(EMAIL).andReturn());
			assertThat(reached.await(10, TimeUnit.SECONDS)).isTrue();

			// 60초 경과를 흉내 내 제한이 풀리고, 요청 B가 새 제한을 얻어 발급·메일까지 끝낸다.
			tokenStore.expireThrottle(AuthTokenStore.PURPOSE_RESET, EMAIL);
			requestReset(EMAIL).andExpect(status().isOk());
			assertThat(mailSender.sentReset()).hasSize(1);
			String bToken = tokenOf(mailSender.lastReset().link());

			proceed.countDown();
			MvcResult aResult = a.get(10, TimeUnit.SECONDS);

			// A도 같은 200이지만 B의 링크를 건드리지 않았고 메일도 보내지 않았다.
			assertThat(aResult.getResponse().getStatus()).isEqualTo(200);
			assertThat(mailSender.sentReset()).hasSize(1);
			assertThat(tokenStore.hasResetToken(bToken)).isTrue();
			assertThat(tokenStore.resetTokenCount()).isEqualTo(1);
			submit(bToken, NEW_PASSWORD, NEW_PASSWORD).andExpect(status().isOk());
		}
		finally {
			proceed.countDown();
			pool.shutdownNow();
		}
	}

	@Test
	void tokenIssueFailureGivesSame200ForRegisteredAndUnregisteredAndSendsNoMail(CapturedOutput output)
			throws Exception {
		tokenStore.failIssue = true;

		MvcResult known = requestReset(EMAIL).andExpect(status().isOk()).andReturn();
		MvcResult unknown = requestReset("nobody@example.com").andExpect(status().isOk()).andReturn();

		assertThat(known.getResponse().getContentAsString(StandardCharsets.UTF_8))
				.isEqualTo(unknown.getResponse().getContentAsString(StandardCharsets.UTF_8));
		assertThat(mailSender.sentReset()).isEmpty();
		assertThat(tokenStore.resetTokenCount()).isZero();
		assertThat(output.getAll()).contains("비밀번호 재설정 토큰 발급 실패 userId=" + userId(EMAIL))
				.contains("cause=IllegalStateException").doesNotContain(EMAIL).doesNotContain("redis down");
	}

	// ---- POST /api/auth/password-reset ----

	@Test
	void resetChangesPasswordSoNewLoginWorksAndOldFails() throws Exception {
		String token = issueToken(EMAIL);

		submit(token, NEW_PASSWORD, NEW_PASSWORD).andExpect(status().isOk())
				.andExpect(jsonPath("$.code").value("SUCCESS"))
				.andExpect(jsonPath("$.data").doesNotExist());

		login(EMAIL, NEW_PASSWORD).andExpect(status().isOk());
		login(EMAIL, OLD_PASSWORD).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("AUTH_INVALID_CREDENTIALS"));
		// 저장된 값은 평문이 아니라 BCrypt 해시다.
		assertThat(userRepository.findByEmail(EMAIL).orElseThrow().getPasswordHash()).startsWith("$2")
				.doesNotContain(NEW_PASSWORD);
		assertThat(tokenStore.hasLatestResetMarker(userId(EMAIL))).isFalse();
	}

	@Test
	void tokenCannotBeReused() throws Exception {
		String token = issueToken(EMAIL);
		submit(token, NEW_PASSWORD, NEW_PASSWORD).andExpect(status().isOk());

		submit(token, "Another!Pass9", "Another!Pass9").andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("AUTH_INVALID_TOKEN"));
		login(EMAIL, NEW_PASSWORD).andExpect(status().isOk());
	}

	@Test
	void unknownTokenIs400InvalidToken() throws Exception {
		submit("no-such-token", NEW_PASSWORD, NEW_PASSWORD).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("AUTH_INVALID_TOKEN"));
	}

	@Test
	void tokenOfDeletedUserIs400InvalidToken() throws Exception {
		String token = issueToken(EMAIL);
		userRepository.deleteAll();

		submit(token, NEW_PASSWORD, NEW_PASSWORD).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("AUTH_INVALID_TOKEN"));
	}

	@Test
	void policyViolationAndMismatchAre400AndKeepTheToken() throws Exception {
		String token = issueToken(EMAIL);

		submit(token, "short", "short").andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
				.andExpect(jsonPath("$.details.fieldErrors[0].field").value("newPassword"))
				.andExpect(jsonPath("$.details.fieldErrors[0].reason").value("PASSWORD_POLICY"))
				.andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.startsWith("비밀번호는 8~64자")));
		submit(token, NEW_PASSWORD, "Different!Pass9").andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
				.andExpect(jsonPath("$.details.fieldErrors[0].field").value("newPasswordConfirm"))
				.andExpect(jsonPath("$.details.fieldErrors[0].reason").value("PASSWORD_MISMATCH"));
		postJson("/api/auth/password-reset", "{\"newPassword\":\"" + NEW_PASSWORD + "\",\"newPasswordConfirm\":\""
				+ NEW_PASSWORD + "\"}").andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.details.fieldErrors[0].field").value("token"))
				.andExpect(jsonPath("$.details.fieldErrors[0].reason").value("REQUIRED"));

		// 토큰은 소비되지 않았으므로 같은 토큰으로 다시 제출하면 성공한다. 비밀번호도 아직 그대로다.
		assertThat(tokenStore.hasResetToken(token)).isTrue();
		login(EMAIL, OLD_PASSWORD).andExpect(status().isOk());
		submit(token, NEW_PASSWORD, NEW_PASSWORD).andExpect(status().isOk());
		login(EMAIL, NEW_PASSWORD).andExpect(status().isOk());
	}

	@Test
	void resetDeletesAllSessionsOfThatUserOnly() throws Exception {
		String s1 = loginAndGetSessionId(EMAIL);
		String s2 = loginAndGetSessionId(EMAIL);
		String other = loginAndGetSessionId(OTHER_EMAIL);
		assertThat(s1).isNotEqualTo(s2);
		assertThat(sessionRepository.findByPrincipalName(String.valueOf(userId(EMAIL)))).containsOnlyKeys(s1, s2);
		String token = issueToken(EMAIL);

		// 재설정 요청에 세션 쿠키가 실려 있어도(로그인 상태에서 링크를 연 경우) 같이 지워진다.
		mockMvc.perform(post("/api/auth/password-reset").contentType(MediaType.APPLICATION_JSON)
				.content("{\"token\":\"" + token + "\",\"newPassword\":\"" + NEW_PASSWORD
						+ "\",\"newPasswordConfirm\":\"" + NEW_PASSWORD + "\"}")
				.cookie(new Cookie("XSRF-TOKEN", "t0ken"))
				.cookie(new Cookie("SESSION", Base64.getEncoder().encodeToString(s1.getBytes(StandardCharsets.UTF_8))))
				.header("X-XSRF-TOKEN", "t0ken")).andExpect(status().isOk());

		assertThat(sessionRepository.findById(s1)).isNull();
		assertThat(sessionRepository.findById(s2)).isNull();
		assertThat(sessionRepository.findById(other)).isNotNull();
		assertThat(sessionRepository.findByPrincipalName(String.valueOf(userId(OTHER_EMAIL)))).containsOnlyKeys(other);
	}

	@Test
	void resetWithAnotherUsersSessionCookieKeepsThatSession() throws Exception {
		String other = loginAndGetSessionId(OTHER_EMAIL);
		String token = issueToken(EMAIL);

		mockMvc.perform(post("/api/auth/password-reset").contentType(MediaType.APPLICATION_JSON)
				.content("{\"token\":\"" + token + "\",\"newPassword\":\"" + NEW_PASSWORD
						+ "\",\"newPasswordConfirm\":\"" + NEW_PASSWORD + "\"}")
				.cookie(new Cookie("XSRF-TOKEN", "t0ken"))
				.cookie(new Cookie("SESSION", Base64.getEncoder().encodeToString(other.getBytes(StandardCharsets.UTF_8))))
				.header("X-XSRF-TOKEN", "t0ken")).andExpect(status().isOk());

		assertThat(sessionRepository.findById(other)).isNotNull();
	}

	@Test
	void secretsAreNotLogged(CapturedOutput output) throws Exception {
		String token = issueToken(EMAIL);
		submit(token, NEW_PASSWORD, NEW_PASSWORD).andExpect(status().isOk());

		// 로그 모드 발송기는 쓰이지 않는 테스트 구성(기록용 발송기)이므로 토큰·비밀번호·이메일이 어디에도 없어야 한다.
		assertThat(output.getAll()).doesNotContain(token).doesNotContain(NEW_PASSWORD).doesNotContain(OLD_PASSWORD)
				.doesNotContain(EMAIL);
	}
}
