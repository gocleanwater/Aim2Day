package com.aim2day.user.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aim2day.user.common.security.SessionUserIdAuthenticationFilter;
import com.aim2day.user.support.AuthTestConfig;
import com.aim2day.user.support.AuthTestConfig.InMemoryTokenStore;
import com.aim2day.user.support.AuthTestConfig.RecordingMailSender;
import com.aim2day.user.support.TestSessionConfig;
import com.aim2day.user.support.TestSessionConfig.IndexedMapSessionRepository;
import com.aim2day.user.user.User;
import com.aim2day.user.user.UserRepository;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.session.FlushMode;
import org.springframework.session.MapSession;
import org.springframework.session.data.redis.RedisIndexedSessionRepository;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 로그인과 비밀번호 재설정이 겹칠 때 이전 비밀번호로 만든 세션이 남지 않는지 검증한다.
 * LoginService를 스파이로 감싸 "1차 비밀번호 확인 직후, 세션 저장 전" 지점에서 로그인 스레드를 멈춘다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({ TestSessionConfig.class, AuthTestConfig.class })
class LoginResetRaceTests {

	private static final String EMAIL = "user@example.com";
	private static final String OLD_PASSWORD = "Aim2day!2026";
	private static final String NEW_PASSWORD = "NewPass!2026";

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
	@MockitoSpyBean
	IndexedMapSessionRepository sessionRepository;
	@Autowired
	RedisIndexedSessionRepository redisSessionRepository;
	@MockitoSpyBean
	LoginService loginService;

	@BeforeEach
	void setUp() {
		userRepository.deleteAll();
		tokenStore.clear();
		mailSender.clear();
		userRepository.saveAndFlush(new User(EMAIL, "정수", passwordEncoder.encode(OLD_PASSWORD)));
	}

	private ResultActions postJson(String path, String body) throws Exception {
		return mockMvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body)
				.cookie(new Cookie("XSRF-TOKEN", "t0ken")).header("X-XSRF-TOKEN", "t0ken"));
	}

	private ResultActions login(String password) throws Exception {
		return postJson("/api/auth/login", "{\"email\":\"" + EMAIL + "\",\"password\":\"" + password + "\"}");
	}

	/** 링크 요청 -> 메일의 토큰으로 새 비밀번호 제출. 재설정을 끝까지 완료한다. */
	private void completeReset() throws Exception {
		postJson("/api/auth/password-reset-requests", "{\"email\":\"" + EMAIL + "\"}").andExpect(status().isOk());
		String link = mailSender.lastReset().link();
		String token = link.substring(link.indexOf("#token=") + "#token=".length());
		postJson("/api/auth/password-reset", "{\"token\":\"" + token + "\",\"newPassword\":\"" + NEW_PASSWORD
				+ "\",\"newPasswordConfirm\":\"" + NEW_PASSWORD + "\"}").andExpect(status().isOk());
	}

	private String userKey() {
		return String.valueOf(userRepository.findByEmail(EMAIL).orElseThrow().getId());
	}

	@Test
	void resetCompletedBetweenPasswordCheckAndSessionSaveLeavesNoSessionAndNoCookie() throws Exception {
		CountDownLatch reached = new CountDownLatch(1);
		CountDownLatch proceed = new CountDownLatch(1);
		AtomicBoolean first = new AtomicBoolean();
		// 진짜 authenticate(이전 비밀번호 확인)를 실행한 뒤, 세션을 만들기 전에 멈춘다.
		doAnswer(invocation -> {
			Object user = invocation.callRealMethod();
			if (first.compareAndSet(false, true)) {
				reached.countDown();
				proceed.await(10, TimeUnit.SECONDS);
			}
			return user;
		}).when(loginService).authenticate(any(), any());

		ExecutorService pool = Executors.newSingleThreadExecutor();
		try {
			Future<MvcResult> loginResult = pool.submit(() -> login(OLD_PASSWORD).andReturn());
			assertThat(reached.await(10, TimeUnit.SECONDS)).isTrue();

			completeReset(); // 로그인이 멈춘 사이 재설정이 비밀번호를 바꾸고 끝난다.
			proceed.countDown();
			MvcResult result = loginResult.get(10, TimeUnit.SECONDS);

			assertThat(result.getResponse().getStatus()).isEqualTo(401);
			assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
					.contains("AUTH_INVALID_CREDENTIALS");
			assertThat(sessionRepository.findByPrincipalName(userKey())).isEmpty();
			// 로그인 쿠키가 발급되지 않는다: SESSION 쿠키가 있더라도 만료(Max-Age=0) 쿠키뿐이다.
			List<String> sessionCookies = result.getResponse().getHeaders(HttpHeaders.SET_COOKIE).stream()
					.filter(h -> h.startsWith("SESSION=")).toList();
			assertThat(sessionCookies).allMatch(h -> h.contains("Max-Age=0"));
			// 새 비밀번호로는 정상 로그인된다(실패가 계정 상태 문제가 아님).
			login(NEW_PASSWORD).andExpect(status().isOk());
		}
		finally {
			proceed.countDown();
			pool.shutdownNow();
		}
	}

	@Test
	void resetAfterLoginFinishedDeletesThatSession() throws Exception {
		MvcResult ok = login(OLD_PASSWORD).andExpect(status().isOk()).andReturn();
		assertThat(ok.getResponse().getHeaders(HttpHeaders.SET_COOKIE)).anyMatch(h -> h.startsWith("SESSION="));
		assertThat(sessionRepository.findByPrincipalName(userKey())).hasSize(1);

		completeReset();

		assertThat(sessionRepository.findByPrincipalName(userKey())).isEmpty();
	}

	@Test
	void recheckFailureOfAnyKindDropsTheJustCreatedSession() throws Exception {
		// 재확인 단계에서 DB 오류 같은 예외가 나도 확인되지 않은 세션을 남기지 않는다.
		doAnswer(invocation -> {
			throw new IllegalStateException("db down");
		}).when(loginService).ensurePasswordUnchanged(any());

		login(OLD_PASSWORD).andExpect(status().isInternalServerError());

		assertThat(sessionRepository.findByPrincipalName(userKey())).isEmpty();
	}

	@Test
	void failureRightAfterUserIdIsStoredDropsTheSessionAndIssuesNoCookie() throws Exception {
		// 운영(IMMEDIATE)처럼 userId setAttribute가 저장소에 반영된 직후 예외가 나는 상황을 만든다.
		// 새로 만들어지는 세션을 스파이로 감싸, userId 저장 시 저장소에 즉시 저장한 뒤 예외를 던진다.
		AtomicBoolean persisted = new AtomicBoolean();
		doAnswer(invocation -> {
			MapSession spySession = spy((MapSession) invocation.callRealMethod());
			doAnswer(set -> {
				set.callRealMethod();
				if (SessionUserIdAuthenticationFilter.USER_ID_ATTRIBUTE.equals(set.getArgument(0))) {
					sessionRepository.save(spySession); // 이 시점부터 저장소에 인증 가능한 세션이 있다.
					persisted.set(true);
					throw new IllegalStateException("redis failure after userId");
				}
				return null;
			}).when(spySession).setAttribute(any(), any());
			return spySession;
		}).when(sessionRepository).createSession();

		MvcResult result = login(OLD_PASSWORD).andExpect(status().isInternalServerError()).andReturn();

		assertThat(persisted).isTrue(); // 테스트 훅이 실제로 동작했다(세션이 한 번은 저장소에 있었다).
		assertThat(sessionRepository.findByPrincipalName(userKey())).isEmpty();
		List<String> sessionCookies = result.getResponse().getHeaders(HttpHeaders.SET_COOKIE).stream()
				.filter(h -> h.startsWith("SESSION=")).toList();
		assertThat(sessionCookies).allMatch(h -> h.contains("Max-Age=0"));
	}

	@Test
	void redisSessionRepositoryFlushesImmediately() {
		// 운영 저장소는 setAttribute 시점에 즉시 Redis에 쓴다(SessionConfig). 로그인의 "저장 후 재확인" 순서의 전제다.
		assertThat(ReflectionTestUtils.getField(redisSessionRepository, "flushMode")).isEqualTo(FlushMode.IMMEDIATE);
	}
}
