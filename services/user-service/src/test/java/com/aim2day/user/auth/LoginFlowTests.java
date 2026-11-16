package com.aim2day.user.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aim2day.user.support.AuthTestConfig;
import com.aim2day.user.support.TestSessionConfig;
import com.aim2day.user.user.User;
import com.aim2day.user.user.UserRepository;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.MapSession;
import org.springframework.session.MapSessionRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest
@AutoConfigureMockMvc
@Import({ TestSessionConfig.class, AuthTestConfig.class })
class LoginFlowTests {

	private static final String EMAIL = "user@example.com";
	private static final String PASSWORD = "Aim2day!2026";

	@Autowired
	MockMvc mockMvc;
	@Autowired
	UserRepository userRepository;
	@Autowired
	PasswordEncoder passwordEncoder;
	@Autowired
	MapSessionRepository sessionRepository;
	@Autowired
	ApplicationContext context;

	/**
	 * 운영에서는 main()이 JVM 기본 시간대를 UTC로 고정한다(UserServiceApplication). 테스트는 main()을 거치지 않으므로
	 * 같은 조건을 맞춘다. 그렇지 않으면 호스트 시간대(예: KST)가 Hibernate의 LocalDateTime 변환에 섞여 값 검증이 어긋난다.
	 * 다른 테스트에 영향이 없도록 끝나면 되돌린다.
	 */
	private static java.util.TimeZone originalZone;

	@org.junit.jupiter.api.BeforeAll
	static void useUtcLikeProduction() {
		originalZone = java.util.TimeZone.getDefault();
		java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"));
	}

	@org.junit.jupiter.api.AfterAll
	static void restoreZone() {
		java.util.TimeZone.setDefault(originalZone);
	}

	/** 가입 직전·직후 UTC 시각. createdAt 값 검증용. */
	private Instant beforeSignup;
	private Instant afterSignup;

	@BeforeEach
	void reset() {
		userRepository.deleteAll();
		beforeSignup = Instant.now();
		userRepository.saveAndFlush(new User(EMAIL, "정수", passwordEncoder.encode(PASSWORD)));
		afterSignup = Instant.now();
	}

	// ---- 요청 도우미 ----

	/** MapSessionRepository 내부 저장 맵(비어 있는지 확인·비우기용). */
	@SuppressWarnings("unchecked")
	private java.util.Map<String, Object> sessionStore() {
		return (java.util.Map<String, Object>) org.springframework.test.util.ReflectionTestUtils
				.getField(sessionRepository, "sessions");
	}

	/** CSRF 쿠키+헤더 쌍을 붙인 POST. 선택적으로 SESSION 쿠키도 보낸다. */
	private ResultActions postWith(String path, String body, String csrf, String sessionCookie) throws Exception {
		var req = post(path).contentType(MediaType.APPLICATION_JSON);
		if (body != null) {
			req.content(body);
		}
		if (csrf != null) {
			req.cookie(new Cookie("XSRF-TOKEN", csrf)).header("X-XSRF-TOKEN", csrf);
		}
		if (sessionCookie != null) {
			req.cookie(new Cookie("SESSION", sessionCookie));
		}
		return mockMvc.perform(req);
	}

	private ResultActions login(String email, String password) throws Exception {
		return postWith("/api/auth/login", loginBody(email, password), "t0ken", null);
	}

	private static String loginBody(String email, String password) {
		return "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}";
	}

	private ResultActions me(String sessionCookie) throws Exception {
		var req = get("/api/users/me");
		if (sessionCookie != null) {
			req.cookie(new Cookie("SESSION", sessionCookie));
		}
		return mockMvc.perform(req);
	}

	/** 응답의 Set-Cookie 헤더에서 이름이 맞는 쿠키의 원문 한 줄을 찾는다. */
	private static String setCookieLine(MockHttpServletResponse response, String name) {
		return response.getHeaders(HttpHeaders.SET_COOKIE).stream()
				.filter(h -> h.startsWith(name + "=")).findFirst().orElse(null);
	}

	private static String cookieValue(MockHttpServletResponse response, String name) {
		String line = setCookieLine(response, name);
		return line == null ? null : line.substring(name.length() + 1).split(";", 2)[0];
	}

	/** SESSION 쿠키 값은 세션 ID의 Base64다(DefaultCookieSerializer 기본). 저장소 조회용으로 되돌린다. */
	private static String sessionIdOf(String cookie) {
		return new String(Base64.getDecoder().decode(cookie), StandardCharsets.UTF_8);
	}

	private static String encode(String sessionId) {
		return Base64.getEncoder().encodeToString(sessionId.getBytes(StandardCharsets.UTF_8));
	}

	private Long userId() {
		return userRepository.findByEmail(EMAIL).orElseThrow().getId();
	}

	/** 로그인해 SESSION 쿠키 값을 돌려준다. */
	private String loginAndGetCookie() throws Exception {
		MvcResult result = login(EMAIL, PASSWORD).andExpect(status().isOk()).andReturn();
		return cookieValue(result.getResponse(), "SESSION");
	}

	// ---- POST /api/auth/login ----

	@Test
	void loginSucceedsAndStoresUserIdAndPrincipalIndexInSession() throws Exception {
		MvcResult result = login(EMAIL, PASSWORD)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.code").value("SUCCESS"))
				.andExpect(jsonPath("$.data.id").value(userId()))
				.andExpect(jsonPath("$.data.email").value(EMAIL))
				.andExpect(jsonPath("$.data.username").value("정수"))
				// 비밀번호·해시는 응답에 없다.
				.andExpect(jsonPath("$.data.passwordHash").doesNotExist())
				.andExpect(jsonPath("$.data.password").doesNotExist())
				.andReturn();

		String line = setCookieLine(result.getResponse(), "SESSION");
		assertThat(line).isNotNull().contains("HttpOnly").contains("SameSite=Lax").contains("Path=/");

		MapSession session = sessionRepository.findById(sessionIdOf(cookieValue(result.getResponse(), "SESSION")));
		assertThat(session).isNotNull();
		Object stored = session.getAttribute("userId");
		assertThat(stored).isInstanceOf(Long.class).isEqualTo(userId());
		assertThat((String) session.getAttribute(FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME))
				.isEqualTo(String.valueOf(userId()));
	}

	@Test
	void sessionHoldsOnlyLongAndStringAndNoSecurityContext() throws Exception {
		String cookie = loginAndGetCookie();
		// 로그인 뒤 인증이 필요한 요청까지 거쳐도 SecurityContext가 세션에 쓰이지 않아야 한다.
		me(cookie).andExpect(status().isOk());

		MapSession session = sessionRepository.findById(sessionIdOf(cookie));
		assertThat(session.getAttributeNames()).doesNotContain("SPRING_SECURITY_CONTEXT");
		for (String name : session.getAttributeNames()) {
			assertThat((Object) session.getAttribute(name)).as(name).isInstanceOfAny(Long.class, String.class);
		}
	}

	@Test
	void loginWithMixedCaseAndSpacesInEmailSucceeds() throws Exception {
		login("  User@Example.COM ", PASSWORD)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.email").value(EMAIL));
	}

	@Test
	void wrongPasswordAndUnknownEmailGiveIdenticalResponses() throws Exception {
		MvcResult wrong = login(EMAIL, "Wrong!Pass1").andExpect(status().isUnauthorized()).andReturn();
		MvcResult unknown = login("nobody@example.com", PASSWORD).andExpect(status().isUnauthorized()).andReturn();

		assertThat(wrong.getResponse().getContentAsString(StandardCharsets.UTF_8))
				.isEqualTo(unknown.getResponse().getContentAsString(StandardCharsets.UTF_8))
				.contains("AUTH_INVALID_CREDENTIALS");
		// 실패한 로그인은 세션을 만들지 않는다.
		assertThat(setCookieLine(wrong.getResponse(), "SESSION")).isNull();
		assertThat(setCookieLine(unknown.getResponse(), "SESSION")).isNull();
	}

	@Test
	void loginValidationFailuresAre400() throws Exception {
		login("not-an-email", PASSWORD).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
				.andExpect(jsonPath("$.details.fieldErrors[0].field").value("email"));
		login(EMAIL, "").andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
				.andExpect(jsonPath("$.details.fieldErrors[0].field").value("password"))
				.andExpect(jsonPath("$.details.fieldErrors[0].reason").value("REQUIRED"));
		login(EMAIL, "a".repeat(65)).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.details.fieldErrors[0].reason").value("TOO_LONG"));
	}

	@Test
	void loginRequiresCsrfToken() throws Exception {
		postWith("/api/auth/login", loginBody(EMAIL, PASSWORD), null, null)
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("CSRF_TOKEN_INVALID"));
	}

	@Test
	void loginWithExistingSessionChangesSessionId() throws Exception {
		// 로그인 전 공격자가 심어 둔 세션이 있는 상황: 같은 ID를 로그인 뒤에도 쓰면 세션 고정이 된다.
		MapSession old = sessionRepository.createSession();
		old.setAttribute("preLogin", "x");
		sessionRepository.save(old);
		String oldId = old.getId();

		MvcResult result = postWith("/api/auth/login", loginBody(EMAIL, PASSWORD), "t0ken", encode(oldId))
				.andExpect(status().isOk()).andReturn();

		String newCookie = cookieValue(result.getResponse(), "SESSION");
		assertThat(newCookie).isNotNull();
		String newId = sessionIdOf(newCookie);
		assertThat(newId).isNotEqualTo(oldId);
		assertThat(sessionRepository.findById(oldId)).isNull();
		MapSession fresh = sessionRepository.findById(newId);
		assertThat((Object) fresh.getAttribute("userId")).isEqualTo(userId());
		// 이전 세션의 속성(preLogin)이 새 세션으로 넘어오지 않고, 계약의 두 속성만 있다.
		assertThat(fresh.getAttributeNames())
				.isEqualTo(Set.of("userId", FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME));
		// 이전 쿠키는 더 이상 인증되지 않는다.
		me(encode(oldId)).andExpect(status().isUnauthorized());
	}

	@Test
	void loginAgainWithLoggedInSessionIssuesNewIdAndOldIdIsRejected() throws Exception {
		String first = loginAndGetCookie();
		MvcResult second = postWith("/api/auth/login", loginBody(EMAIL, PASSWORD), "t0ken", first)
				.andExpect(status().isOk()).andReturn();
		String secondCookie = cookieValue(second.getResponse(), "SESSION");
		assertThat(secondCookie).isNotEqualTo(first);
		me(first).andExpect(status().isUnauthorized());
		me(secondCookie).andExpect(status().isOk());
	}

	@Test
	void authenticatedRequestsKeepSessionId() throws Exception {
		// 회귀 방지(공유 계약 §4): 인증된 요청마다 세션 ID가 바뀌면 안 된다.
		String cookie = loginAndGetCookie();
		for (int i = 0; i < 3; i++) {
			MvcResult r = me(cookie).andExpect(status().isOk()).andReturn();
			assertThat(setCookieLine(r.getResponse(), "SESSION")).isNull();
		}
		assertThat(sessionRepository.findById(sessionIdOf(cookie))).isNotNull();
	}

	@Test
	void requestWithoutSessionDoesNotCreateOne() throws Exception {
		MvcResult r = me(null).andExpect(status().isUnauthorized()).andReturn();
		assertThat(setCookieLine(r.getResponse(), "SESSION")).isNull();
	}

	// ---- GET /api/users/me ----

	@Test
	void meReturnsProfileWithUtcCreatedAt() throws Exception {
		String cookie = loginAndGetCookie();
		String body = me(cookie).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
		Matcher m = Pattern.compile("\"createdAt\":\"([^\"]+)\"").matcher(body);
		assertThat(m.find()).isTrue();
		// DATETIME은 초 단위로 잘리므로 하한은 가입 직전 시각을 초로 내린 값이다. DB 세션이 UTC가 아니면 몇 시간 어긋나 실패한다.
		Instant createdAt = Instant.parse(m.group(1));
		assertThat(createdAt).isBetween(beforeSignup.truncatedTo(ChronoUnit.SECONDS).minusSeconds(1),
				afterSignup.plusSeconds(1));
		me(cookie)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.id").value(userId()))
				.andExpect(jsonPath("$.data.email").value(EMAIL))
				.andExpect(jsonPath("$.data.username").value("정수"))
				.andExpect(jsonPath("$.data.createdAt").value(org.hamcrest.Matchers.matchesPattern(
						"\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z")))
				.andExpect(jsonPath("$.data.passwordHash").doesNotExist());
	}

	@Test
	void meWithoutSessionIs401Unauthenticated() throws Exception {
		me(null).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
	}

	@Test
	void meWhenUserWasDeletedInvalidatesSessionAnd401() throws Exception {
		String cookie = loginAndGetCookie();
		userRepository.deleteAll();

		me(cookie).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
		assertThat(sessionRepository.findById(sessionIdOf(cookie))).isNull();
	}

	// ---- POST /api/auth/logout ----

	@Test
	void logoutDeletesSessionAndExpiresCookie() throws Exception {
		String cookie = loginAndGetCookie();

		MvcResult result = postWith("/api/auth/logout", null, "t0ken", cookie)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.code").value("SUCCESS"))
				.andExpect(jsonPath("$.data").doesNotExist())
				.andReturn();

		String line = setCookieLine(result.getResponse(), "SESSION");
		assertThat(line).isNotNull().contains("Max-Age=0");
		assertThat(sessionRepository.findById(sessionIdOf(cookie))).isNull();
		// 같은 쿠키로 다시 요청하면 401.
		me(cookie).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
	}

	@Test
	void logoutWithoutSessionIs200() throws Exception {
		MvcResult r = postWith("/api/auth/logout", null, "t0ken", null)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.code").value("SUCCESS"))
				.andReturn();
		assertThat(setCookieLine(r.getResponse(), "SESSION")).isNull();
	}

	@Test
	void logoutWithExpiredSessionCookieStillExpiresCookie() throws Exception {
		sessionStore().clear();
		// 저장소에 없는 세션 ID(만료·삭제된 세션)를 담은 쿠키.
		MvcResult r = postWith("/api/auth/logout", null, "t0ken", encode("no-such-session"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.code").value("SUCCESS"))
				.andReturn();
		String line = setCookieLine(r.getResponse(), "SESSION");
		assertThat(line).isNotNull().contains("Max-Age=0").contains("HttpOnly").contains("SameSite=Lax")
				.contains("Path=/");
		// 새 세션을 만들지 않는다(저장소가 비어 있다).
		assertThat(sessionStore()).isEmpty();
	}

	@Test
	void logoutRequiresCsrfToken() throws Exception {
		String cookie = loginAndGetCookie();
		postWith("/api/auth/logout", null, null, cookie)
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("CSRF_TOKEN_INVALID"));
		// CSRF 실패 요청은 세션을 건드리지 않는다.
		assertThat(sessionRepository.findById(sessionIdOf(cookie))).isNotNull();
	}

	// ---- CSRF 동작 ----

	@Test
	void logoutRightAfterLoginPassesWithCurrentCsrfCookieValue() throws Exception {
		MvcResult loginResult = login(EMAIL, PASSWORD).andExpect(status().isOk()).andReturn();
		String sessionCookie = cookieValue(loginResult.getResponse(), "SESSION");
		// 프론트는 매 요청 때 쿠키의 "현재 값"을 헤더에 넣는다(API 명세 §2.2). 로그인 응답이 XSRF-TOKEN을 새로 내렸다면
		// 그 값을, 내리지 않았다면 기존 값을 쓴다. 어느 쪽이든 통과해야 한다.
		String current = cookieValue(loginResult.getResponse(), "XSRF-TOKEN");
		if (current == null) {
			current = "t0ken";
		}
		postWith("/api/auth/logout", null, current, sessionCookie).andExpect(status().isOk());
	}

	// ---- 기본 사용자 자동 구성 ----

	@Test
	void noGeneratedDefaultUserDetailsService() {
		// UserDetailsServiceAutoConfiguration이 제외되어 기본 사용자와 "generated security password" 로그가 없다.
		assertThat(context.getBeanNamesForType(UserDetailsService.class)).isEmpty();
	}
}
