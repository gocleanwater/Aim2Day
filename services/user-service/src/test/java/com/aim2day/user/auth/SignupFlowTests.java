package com.aim2day.user.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aim2day.user.auth.token.AuthTokenStore;
import com.aim2day.user.support.AuthTestConfig;
import com.aim2day.user.support.AuthTestConfig.InMemoryTokenStore;
import com.aim2day.user.support.AuthTestConfig.RecordingMailSender;
import com.aim2day.user.support.TestSessionConfig;
import com.aim2day.user.user.User;
import com.aim2day.user.user.UserRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.MapSession;
import org.springframework.session.MapSessionRepository;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest
@AutoConfigureMockMvc
@Import({ TestSessionConfig.class, AuthTestConfig.class })
@ExtendWith(OutputCaptureExtension.class)
class SignupFlowTests {

	private static final String EMAIL = "user@example.com";
	private static final String PASSWORD = "Aim2day!2026";
	private static final String POLICY_MESSAGE = "비밀번호는 8~64자이며 영문 대문자, 영문 소문자, 숫자, 기호를 각각 1자 이상 포함해야 합니다. 공백·한글·이모지는 쓸 수 없습니다.";
	private static final Pattern TOKEN_PATTERN = Pattern.compile("\"signupToken\":\"([^\"]+)\"");

	@Autowired
	MockMvc mockMvc;
	@MockitoSpyBean
	UserRepository userRepository;
	@MockitoSpyBean
	LoginService loginService;
	@Autowired
	PasswordEncoder passwordEncoder;
	@Autowired
	InMemoryTokenStore tokenStore;
	@Autowired
	RecordingMailSender mailSender;

	@Autowired
	MapSessionRepository sessionRepository;

	@SuppressWarnings("unchecked")
	private java.util.Map<String, Object> sessionStore() {
		return (java.util.Map<String, Object>) org.springframework.test.util.ReflectionTestUtils
				.getField(sessionRepository, "sessions");
	}

	private static String setCookieLine(org.springframework.mock.web.MockHttpServletResponse response, String name) {
		return response.getHeaders(org.springframework.http.HttpHeaders.SET_COOKIE).stream()
				.filter(h -> h.startsWith(name + "=")).findFirst().orElse(null);
	}

	private static String cookieValue(org.springframework.mock.web.MockHttpServletResponse response, String name) {
		String line = setCookieLine(response, name);
		return line == null ? null : line.substring(name.length() + 1).split(";", 2)[0];
	}

	/** SESSION 쿠키 값은 세션 ID의 Base64다(DefaultCookieSerializer 기본). */
	private static String sessionIdOf(String cookie) {
		return new String(java.util.Base64.getDecoder().decode(cookie), java.nio.charset.StandardCharsets.UTF_8);
	}

	private ResultActions postJson(String path, String body, String sessionId) throws Exception {
		return mockMvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body)
				.cookie(new jakarta.servlet.http.Cookie("XSRF-TOKEN", "t0ken"))
				.cookie(new jakarta.servlet.http.Cookie("SESSION", java.util.Base64.getEncoder()
						.encodeToString(sessionId.getBytes(java.nio.charset.StandardCharsets.UTF_8))))
				.header("X-XSRF-TOKEN", "t0ken"));
	}

	@BeforeEach
	void reset() {
		sessionStore().clear();
		userRepository.deleteAll();
		tokenStore.clear();
		mailSender.clear();
	}

	/**
	 * CSRF 쿠키+헤더 쌍을 직접 만든다. spa() 설정은 쿠키 값을 그대로 헤더로 받으므로
	 * 임의 값을 같은 값으로 두 곳에 넣으면 통과한다(운영에서는 GET /api/auth/csrf가 발급).
	 */
	private ResultActions postJson(String path, String body) throws Exception {
		MockHttpServletRequestBuilder req = post(path).contentType(MediaType.APPLICATION_JSON).content(body)
				.cookie(new jakarta.servlet.http.Cookie("XSRF-TOKEN", "t0ken"))
				.header("X-XSRF-TOKEN", "t0ken");
		return mockMvc.perform(req);
	}

	private ResultActions requestCode(String email) throws Exception {
		return postJson("/api/auth/email-codes", "{\"email\":\"" + email + "\"}");
	}

	private ResultActions verify(String email, String code) throws Exception {
		return postJson("/api/auth/email-codes/verify", "{\"email\":\"" + email + "\",\"code\":\"" + code + "\"}");
	}

	private ResultActions signup(String token, String password, String confirm) throws Exception {
		return postJson("/api/auth/signup", "{\"signupToken\":\"" + token + "\",\"username\":\"정수\",\"password\":\""
				+ password + "\",\"passwordConfirm\":\"" + confirm + "\"}");
	}

	/** 코드 요청 -> 메일에서 번호 꺼내기. */
	private String issueCode(String email) throws Exception {
		requestCode(email).andExpect(status().isOk());
		return mailSender.last().code();
	}

	/** 인증까지 마쳐 가입권을 얻는다. */
	private String obtainTicket(String email) throws Exception {
		String code = issueCode(email);
		MvcResult result = verify(email, code).andExpect(status().isOk()).andReturn();
		Matcher m = TOKEN_PATTERN.matcher(result.getResponse().getContentAsString());
		assertThat(m.find()).isTrue();
		return m.group(1);
	}

	private static String wrongOf(String code) {
		return code.equals("000000") ? "111111" : "000000";
	}

	// ---- POST /api/auth/email-codes ----

	@Test
	void requestCodeSendsOneMailWithSixDigitCode() throws Exception {
		requestCode("  User@Example.com ")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.code").value("SUCCESS"))
				.andExpect(jsonPath("$.data.expiresInSeconds").value(600));

		assertThat(mailSender.sent()).hasSize(1);
		assertThat(mailSender.last().to()).isEqualTo(EMAIL); // 정규화된 이메일
		assertThat(mailSender.last().code()).matches("[0-9]{6}");
		// 번호는 원문이 아니라 해시로 저장된다.
		assertThat(tokenStore.storesRawCode(EMAIL, mailSender.last().code())).isFalse();
	}

	@Test
	void requestCodeForRegisteredEmailIs409AndSendsNothing() throws Exception {
		userRepository.save(new User(EMAIL, "기존", passwordEncoder.encode(PASSWORD)));

		requestCode(EMAIL)
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("AUTH_EMAIL_EXISTS"));
		assertThat(mailSender.sent()).isEmpty();
		// 제한 키를 소모하지 않는다(메일을 보내지 않았으므로).
		assertThat(tokenStore.isThrottled(AuthTokenStore.PURPOSE_SIGNUP, EMAIL)).isFalse();
	}

	@Test
	void requestCodeValidationFailsForBadEmail() throws Exception {
		requestCode("not-an-email")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
				.andExpect(jsonPath("$.details.fieldErrors[0].field").value("email"))
				.andExpect(jsonPath("$.details.fieldErrors[0].reason").value("INVALID_FORMAT"));
		assertThat(mailSender.sent()).isEmpty();
	}

	@Test
	void requestCodeWithin60SecondsIs429WithRetryAfter() throws Exception {
		requestCode(EMAIL).andExpect(status().isOk());

		requestCode(EMAIL)
				.andExpect(status().isTooManyRequests())
				.andExpect(jsonPath("$.code").value("TOO_MANY_REQUESTS"))
				.andExpect(jsonPath("$.details.retryAfterSeconds").value(60));
		assertThat(mailSender.sent()).hasSize(1);
	}

	@Test
	void mailFailureIs502AndAllowsImmediateRetry() throws Exception {
		mailSender.failNext(true);
		requestCode(EMAIL)
				.andExpect(status().isBadGateway())
				.andExpect(jsonPath("$.code").value("AUTH_MAIL_SEND_FAILED"))
				.andExpect(jsonPath("$.details").doesNotExist());
		// 이번 요청이 저장한 번호와 제한이 지워져 있다.
		assertThat(tokenStore.hasCode(EMAIL)).isFalse();
		assertThat(tokenStore.isThrottled(AuthTokenStore.PURPOSE_SIGNUP, EMAIL)).isFalse();

		mailSender.failNext(false);
		requestCode(EMAIL).andExpect(status().isOk());
		assertThat(mailSender.sent()).hasSize(1);
	}

	@Test
	void newCodeInvalidatesPreviousCode() throws Exception {
		String first = issueCode(EMAIL);
		// 60초 제한을 풀어 두 번째 번호를 받는다(시간 경과를 흉내 낸다).
		tokenStore.expireThrottle(AuthTokenStore.PURPOSE_SIGNUP, EMAIL);
		String second = issueCode(EMAIL);
		// 두 번호가 우연히 같으면 이 테스트로는 구분할 수 없으므로 다시 받는다(확률 1/100만).
		while (first.equals(second)) {
			tokenStore.expireThrottle(AuthTokenStore.PURPOSE_SIGNUP, EMAIL);
			second = issueCode(EMAIL);
		}

		verify(EMAIL, first)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("AUTH_INVALID_CODE"))
				// 새 번호가 시도 횟수도 새로 시작했으므로 이전 번호의 시도가 이어지지 않는다.
				.andExpect(jsonPath("$.details.remainingAttempts").value(4));
		verify(EMAIL, second).andExpect(status().isOk());
	}

	// ---- POST /api/auth/email-codes/verify ----

	@Test
	void wrongCodeDecrementsRemainingAttemptsThenExpires() throws Exception {
		String code = issueCode(EMAIL);
		String wrong = wrongOf(code);

		for (int expected = 4; expected >= 1; expected--) {
			verify(EMAIL, wrong)
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.code").value("AUTH_INVALID_CODE"))
					.andExpect(jsonPath("$.details.remainingAttempts").value(expected));
		}
		// 5번째 실패는 AUTH_CODE_EXPIRED이고 details는 null이다.
		verify(EMAIL, wrong)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("AUTH_CODE_EXPIRED"))
				.andExpect(jsonPath("$.details").doesNotExist());
		// 5회 실패 뒤에는 맞는 번호도 거부한다.
		verify(EMAIL, code)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("AUTH_CODE_EXPIRED"));
	}

	@Test
	void correctCodeOnFifthAttemptStillSucceeds() throws Exception {
		String code = issueCode(EMAIL);
		for (int i = 0; i < 4; i++) {
			verify(EMAIL, wrongOf(code)).andExpect(status().isBadRequest());
		}
		verify(EMAIL, code).andExpect(status().isOk());
	}

	@Test
	void verifyWithoutIssuedCodeIsExpired() throws Exception {
		verify(EMAIL, "123456")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("AUTH_CODE_EXPIRED"));
	}

	@Test
	void verifyAfterTtlIsExpired() throws Exception {
		String code = issueCode(EMAIL);
		tokenStore.expireCode(EMAIL);
		verify(EMAIL, code)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("AUTH_CODE_EXPIRED"));
	}

	@Test
	void verifyRejectsMalformedCode() throws Exception {
		verify(EMAIL, "12345")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
				.andExpect(jsonPath("$.details.fieldErrors[0].field").value("code"));
		verify(EMAIL, "12345a").andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
	}

	@Test
	void successIssuesTicketAndCodeCannotBeReused() throws Exception {
		String code = issueCode(EMAIL);

		MvcResult result = verify(EMAIL, code)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.signupToken").isString())
				.andExpect(jsonPath("$.data.expiresInSeconds").value(1800))
				.andReturn();
		Matcher m = TOKEN_PATTERN.matcher(result.getResponse().getContentAsString());
		assertThat(m.find()).isTrue();
		// 가입권은 32바이트 난수의 Base64URL(패딩 없음) = 43자이고, 저장소에는 원문이 아니라 해시로 있다.
		assertThat(m.group(1)).matches("[A-Za-z0-9_-]{43}");
		assertThat(tokenStore.containsRawToken(m.group(1))).isFalse();
		assertThat(tokenStore.ticketCount()).isEqualTo(1);

		// 성공 시 번호는 삭제되므로 같은 번호는 다시 쓸 수 없다.
		verify(EMAIL, code)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("AUTH_CODE_EXPIRED"));
	}

	@Test
	void concurrentWrongAttemptsNeverExceedFive() throws Exception {
		String code = issueCode(EMAIL);
		String wrong = wrongOf(code);
		int threads = 40;
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		CountDownLatch start = new CountDownLatch(1);
		List<Future<AuthTokenStore.CodeCheck>> futures = new ArrayList<>();
		for (int i = 0; i < threads; i++) {
			futures.add(pool.submit(() -> {
				start.await();
				return tokenStore.verifyCode(EMAIL, wrong);
			}));
		}
		start.countDown();
		int wrongCount = 0;
		int expiredCount = 0;
		for (Future<AuthTokenStore.CodeCheck> f : futures) {
			AuthTokenStore.CodeCheck check = f.get(10, TimeUnit.SECONDS);
			switch (check.status()) {
				case WRONG -> wrongCount++;
				case EXPIRED -> expiredCount++;
				default -> throw new AssertionError("틀린 번호가 성공해서는 안 된다");
			}
		}
		pool.shutdown();
		// 시도 5회 중 1~4번째는 WRONG, 5번째와 이후는 모두 EXPIRED. 5회를 넘게 비교되는 요청은 없다.
		assertThat(wrongCount).isEqualTo(4);
		assertThat(expiredCount).isEqualTo(threads - 4);
		// 소진 뒤에는 맞는 번호도 거부한다.
		assertThat(tokenStore.verifyCode(EMAIL, code).status()).isEqualTo(AuthTokenStore.CodeStatus.EXPIRED);
	}

	@Test
	void concurrentCorrectCodeIssuesExactlyOneTicket() throws Exception {
		String code = issueCode(EMAIL);
		int threads = 20;
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		CountDownLatch start = new CountDownLatch(1);
		List<Future<Integer>> futures = new ArrayList<>();
		for (int i = 0; i < threads; i++) {
			futures.add(pool.submit(() -> {
				start.await();
				return verify(EMAIL, code).andReturn().getResponse().getStatus();
			}));
		}
		start.countDown();
		int ok = 0;
		for (Future<Integer> f : futures) {
			if (f.get(20, TimeUnit.SECONDS) == 200) {
				ok++;
			}
		}
		pool.shutdown();
		assertThat(ok).isEqualTo(1);
		assertThat(tokenStore.ticketCount()).isEqualTo(1);
	}

	/** 오답 하나와 정답 하나를 동시에 보낸다. 정답은 어느 쪽이 먼저 실행돼도 성공해야 한다(남은 횟수가 2이므로 오답이 먼저 와도 1이 남는다). */
	@Test
	void correctCodeSucceedsEvenWhenWrongAttemptsRaceWithIt() throws Exception {
		String code = issueCode(EMAIL);
		for (int i = 0; i < 3; i++) {
			verify(EMAIL, wrongOf(code)).andExpect(status().isBadRequest());
		}
		ExecutorService pool = Executors.newFixedThreadPool(2);
		CountDownLatch start = new CountDownLatch(1);
		Future<AuthTokenStore.CodeCheck> good = pool.submit(() -> {
			start.await();
			return tokenStore.verifyCode(EMAIL, code);
		});
		Future<AuthTokenStore.CodeCheck> bad = pool.submit(() -> {
			start.await();
			return tokenStore.verifyCode(EMAIL, wrongOf(code));
		});
		start.countDown();
		assertThat(good.get(10, TimeUnit.SECONDS).status()).isEqualTo(AuthTokenStore.CodeStatus.OK);
		bad.get(10, TimeUnit.SECONDS);
		pool.shutdown();
		assertThat(tokenStore.ticketCount()).isEqualTo(1);
	}

	/** 마지막 1회가 남은 상태에서 정답과 오답 여러 개가 겹치면 순서에 따라 갈리지만, 가입권은 정답 성공 시에만 정확히 1개다. */
	@Test
	void lastAttemptRaceNeverIssuesMoreThanOneTicketAndWrongNeverSucceeds() throws Exception {
		String code = issueCode(EMAIL);
		for (int i = 0; i < 4; i++) {
			verify(EMAIL, wrongOf(code)).andExpect(status().isBadRequest());
		}
		int threads = 12;
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		CountDownLatch start = new CountDownLatch(1);
		Future<AuthTokenStore.CodeCheck> good = pool.submit(() -> {
			start.await();
			return tokenStore.verifyCode(EMAIL, code);
		});
		List<Future<AuthTokenStore.CodeCheck>> bads = new ArrayList<>();
		for (int i = 0; i < threads - 1; i++) {
			bads.add(pool.submit(() -> {
				start.await();
				return tokenStore.verifyCode(EMAIL, wrongOf(code));
			}));
		}
		start.countDown();
		boolean goodOk = good.get(10, TimeUnit.SECONDS).status() == AuthTokenStore.CodeStatus.OK;
		for (Future<AuthTokenStore.CodeCheck> f : bads) {
			assertThat(f.get(10, TimeUnit.SECONDS).status()).isNotEqualTo(AuthTokenStore.CodeStatus.OK);
		}
		pool.shutdown();
		assertThat(tokenStore.ticketCount()).isEqualTo(goodOk ? 1 : 0);
	}

	// ---- POST /api/auth/signup ----

	@Test
	void signupCreatesUserWithBcryptPasswordAndLogsIn() throws Exception {
		String ticket = obtainTicket("  User@Example.com ");

		MvcResult result = signup(ticket, PASSWORD, PASSWORD)
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.code").value("SUCCESS"))
				.andExpect(jsonPath("$.data.id").isNumber())
				.andExpect(jsonPath("$.data.email").value(EMAIL))
				.andExpect(jsonPath("$.data.username").value("정수"))
				.andExpect(jsonPath("$.data.password").doesNotExist())
				.andExpect(jsonPath("$.data.passwordHash").doesNotExist())
				.andReturn();

		// 자동 로그인: SESSION 쿠키가 내려오고, 그 세션에 userId와 principal 인덱스가 로그인과 같은 형태로 들어 있다.
		String line = setCookieLine(result.getResponse(), "SESSION");
		assertThat(line).isNotNull().contains("HttpOnly").contains("SameSite=Lax").contains("Path=/");
		User user = userRepository.findByEmail(EMAIL).orElseThrow();
		MapSession session = sessionRepository.findById(sessionIdOf(cookieValue(result.getResponse(), "SESSION")));
		assertThat(session).isNotNull();
		assertThat(session.<Object>getAttribute("userId")).isInstanceOf(Long.class).isEqualTo(user.getId());
		assertThat(session.<String>getAttribute(FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME))
				.isEqualTo(String.valueOf(user.getId()));
		assertThat(session.getAttributeNames())
				.containsExactlyInAnyOrder("userId", FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME);

		assertThat(user.getUsername()).isEqualTo("정수");
		assertThat(user.getPasswordHash()).isNotEqualTo(PASSWORD).startsWith("$2");
		assertThat(passwordEncoder.matches(PASSWORD, user.getPasswordHash())).isTrue();
	}

	@Test
	void signupStillReturns201WithoutSessionWhenPasswordRecheckFails() throws Exception {
		String ticket = obtainTicket(EMAIL);
		// 계정 커밋 뒤 세션 저장 전에 비밀번호 재설정이 끝난 상황: 재확인이 불일치로 실패한다.
		doThrow(new com.aim2day.user.common.response.BusinessException(
				com.aim2day.user.common.response.ErrorCode.AUTH_INVALID_CREDENTIALS))
				.when(loginService).ensurePasswordUnchanged(any());

		MvcResult result = signup(ticket, PASSWORD, PASSWORD)
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.data.id").isNumber())
				.andExpect(jsonPath("$.data.email").value(EMAIL))
				.andExpect(jsonPath("$.data.username").value("정수"))
				.andReturn();

		String line = setCookieLine(result.getResponse(), "SESSION");
		assertThat(line == null || line.contains("Max-Age=0")).isTrue();
		assertThat(sessionStore()).isEmpty();
		assertThat(userRepository.findByEmail(EMAIL)).isPresent();
	}

	@Test
	void sessionFromSignupAuthenticatesMeEndpoint()throws Exception {
		String ticket = obtainTicket(EMAIL);
		MvcResult result = signup(ticket, PASSWORD, PASSWORD).andExpect(status().isCreated()).andReturn();
		String cookie = cookieValue(result.getResponse(), "SESSION");

		mockMvc.perform(get("/api/users/me").cookie(new jakarta.servlet.http.Cookie("SESSION", cookie)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.email").value(EMAIL))
				.andExpect(jsonPath("$.data.username").value("정수"));
	}

	@Test
	void signupReissuesSessionIdAndDropsPreSignupAttributes() throws Exception {
		String ticket = obtainTicket(EMAIL);
		// 가입 전에 공격자가 심어 둔 세션(고정 공격 가정). 가입 뒤 ID가 달라지고 속성도 넘어오지 않아야 한다.
		MapSession planted = sessionRepository.createSession();
		planted.setAttribute("evil", "x");
		sessionRepository.save(planted);

		MvcResult result = postJson("/api/auth/signup", "{\"signupToken\":\"" + ticket
				+ "\",\"username\":\"정수\",\"password\":\"" + PASSWORD + "\",\"passwordConfirm\":\"" + PASSWORD + "\"}",
				planted.getId()).andExpect(status().isCreated()).andReturn();

		String newId = sessionIdOf(cookieValue(result.getResponse(), "SESSION"));
		assertThat(newId).isNotEqualTo(planted.getId());
		assertThat(sessionRepository.findById(planted.getId())).isNull();
		assertThat(sessionRepository.findById(newId).getAttributeNames()).doesNotContain("evil");
	}

	@Test
	void failedSignupsCreateNoSession() throws Exception {
		// 잘못된 가입권
		signup("no-such-ticket", PASSWORD, PASSWORD).andExpect(status().isBadRequest());
		// 검증 실패
		String ticket = obtainTicket(EMAIL);
		signup(ticket, "short1!", "short1!").andExpect(status().isBadRequest());
		signup(ticket, PASSWORD, PASSWORD + "x").andExpect(status().isBadRequest());
		// 이메일 중복
		userRepository.save(new User(EMAIL, "먼저", passwordEncoder.encode(PASSWORD)));
		MvcResult conflict = signup(ticket, PASSWORD, PASSWORD).andExpect(status().isConflict()).andReturn();
		assertThat(setCookieLine(conflict.getResponse(), "SESSION")).isNull();
		assertThat(sessionStore()).isEmpty();
	}

	@Test
	void ticketCannotBeReused() throws Exception {
		String ticket = obtainTicket(EMAIL);
		signup(ticket, PASSWORD, PASSWORD).andExpect(status().isCreated());

		signup(ticket, PASSWORD, PASSWORD)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("AUTH_INVALID_TOKEN"))
				.andExpect(jsonPath("$.details").doesNotExist());
		assertThat(userRepository.count()).isEqualTo(1);
	}

	@Test
	void unknownTicketIsInvalidToken() throws Exception {
		signup("no-such-ticket", PASSWORD, PASSWORD)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("AUTH_INVALID_TOKEN"));
	}

	@Test
	void invalidInputDoesNotConsumeTicket() throws Exception {
		String ticket = obtainTicket(EMAIL);

		signup(ticket, "short1!", "short1!")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
				.andExpect(jsonPath("$.details.fieldErrors[0].field").value("password"))
				.andExpect(jsonPath("$.message").value(POLICY_MESSAGE))
				.andExpect(jsonPath("$.details.fieldErrors[0].reason").value("PASSWORD_POLICY"));
		signup(ticket, PASSWORD, PASSWORD + "x")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.details.fieldErrors[0].field").value("passwordConfirm"))
				.andExpect(jsonPath("$.details.fieldErrors[0].reason").value("PASSWORD_MISMATCH"))
				.andExpect(jsonPath("$.message").value("입력값이 올바르지 않습니다."));
		assertThat(tokenStore.ticketCount()).isEqualTo(1);

		// 같은 가입권으로 고쳐서 다시 제출하면 성공한다.
		signup(ticket, PASSWORD, PASSWORD).andExpect(status().isCreated());
	}

	@Test
	void passwordWithKoreanSpaceOrEmojiIsPolicyViolation() throws Exception {
		String ticket = obtainTicket(EMAIL);
		for (String bad : new String[] { "Aim2day!한글비번", "Aim2day! 2026", "Aim2day!2026😀",
				"abcdefgh1234", "abcdefg1!", "ABCDEFG1!", "abcdefgh!!!!", "ABCDEFGH!!!!", "12345678!!!!", "Aa1!" + "a".repeat(61) }) {
			signup(ticket, bad, bad)
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
					.andExpect(jsonPath("$.details.fieldErrors[0].field").value("password"))
					.andExpect(jsonPath("$.message").value(POLICY_MESSAGE))
					.andExpect(jsonPath("$.details.fieldErrors[0].reason").value("PASSWORD_POLICY"));
		}
		// 64자 경계는 허용한다.
		String max = "Aa1!" + "a".repeat(60);
		signup(ticket, max, max).andExpect(status().isCreated());
	}

	@Test
	void signupWhenEmailTakenInMeantimeIs409() throws Exception {
		String ticket = obtainTicket(EMAIL);
		userRepository.save(new User(EMAIL, "먼저", passwordEncoder.encode(PASSWORD)));

		signup(ticket, PASSWORD, PASSWORD)
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("AUTH_EMAIL_EXISTS"));
	}

	@Test
	void emailUniqueViolationIs409AndEmailNeverReachesLogs(CapturedOutput output) throws Exception {
		String ticket = obtainTicket(EMAIL);
		userRepository.save(new User(EMAIL, "먼저", passwordEncoder.encode(PASSWORD)));
		// 선조회를 건너뛰고 INSERT까지 가게 해, 동시 가입으로 UNIQUE 제약에 걸리는 경로를 결정적으로 재현한다.
		doReturn(false).when(userRepository).existsByEmail(EMAIL);

		signup(ticket, PASSWORD, PASSWORD)
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("AUTH_EMAIL_EXISTS"));
		assertThat(output.getAll()).doesNotContain(EMAIL);
	}

	@Test
	void otherIntegrityViolationIs500NotConflict() throws Exception {
		String ticket = obtainTicket(EMAIL);
		doThrow(new DataIntegrityViolationException("NULL not allowed for column \"USERNAME\""))
				.when(userRepository).saveAndFlush(any(User.class));

		signup(ticket, PASSWORD, PASSWORD)
				.andExpect(status().isInternalServerError())
				.andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
	}

	@Test
	void concurrentSignupsWithSameEmailYieldOneCreatedAndOneConflict(CapturedOutput output) throws Exception {
		// 같은 이메일로 인증을 두 번 마친 가입권 두 개(재요청 제한이 풀린 뒤의 상황)를 저장소에서 바로 만든다.
		String ticketA = tokenStore.issueTicket(EMAIL);
		String ticketB = tokenStore.issueTicket(EMAIL);
		ExecutorService pool = Executors.newFixedThreadPool(2);
		CountDownLatch start = new CountDownLatch(1);
		List<Future<Integer>> futures = new ArrayList<>();
		for (String ticket : List.of(ticketA, ticketB)) {
			futures.add(pool.submit(() -> {
				start.await();
				return signup(ticket, PASSWORD, PASSWORD).andReturn().getResponse().getStatus();
			}));
		}
		start.countDown();
		List<Integer> statuses = new ArrayList<>();
		for (Future<Integer> f : futures) {
			statuses.add(f.get(20, TimeUnit.SECONDS));
		}
		pool.shutdown();

		assertThat(statuses).containsExactlyInAnyOrder(201, 409);
		assertThat(userRepository.count()).isEqualTo(1);
		assertThat(output.getAll()).doesNotContain(EMAIL);
	}

	@Test
	void signupRequiresCsrfToken() throws Exception {
		mockMvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
				.content("{\"signupToken\":\"x\"}"))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("CSRF_TOKEN_INVALID"));
	}
}
