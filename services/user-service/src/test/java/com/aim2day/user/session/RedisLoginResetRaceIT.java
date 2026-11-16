package com.aim2day.user.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aim2day.user.auth.LoginService;
import com.aim2day.user.auth.token.AuthTokenStore;
import com.aim2day.user.auth.token.TokenCodec;
import com.aim2day.user.support.AuthTestConfig.RecordingMailSender;
import com.aim2day.user.user.User;
import com.aim2day.user.user.UserRepository;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.session.data.redis.RedisIndexedSessionRepository;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 실제 Redis 세션 저장소와 실제 RedisAuthTokenStore(Lua 포함)로 로그인·비밀번호 재설정 경합을 검증한다.
 * LoginResetRaceTests와 같은 시나리오이며, 메모리 저장소가 아니라 운영 구성에서 세션이 0개가 되는지를 증명한다.
 *
 * <p>IT_REDIS_HOST가 있을 때만 실행한다. 메일 발송기만 기록용으로 바꾸고(링크에서 토큰을 얻기 위해) 나머지 빈은 운영 그대로다.
 * DB는 테스트 설정의 H2(메모리)다. 테스트가 만든 사용자·세션·auth:* 키는 끝나고 지운다.
 */
@EnabledIfEnvironmentVariable(named = "IT_REDIS_HOST", matches = ".+")
@SpringBootTest(properties = "spring.data.redis.host=${IT_REDIS_HOST}")
@AutoConfigureMockMvc
@Import(RedisLoginResetRaceIT.MailConfig.class)
class RedisLoginResetRaceIT {

	@TestConfiguration
	static class MailConfig {
		@Bean
		@Primary
		RecordingMailSender recordingMailSender() {
			return new RecordingMailSender();
		}
	}

	private static final String OLD_PASSWORD = "Aim2day!2026";
	private static final String NEW_PASSWORD = "NewPass!2026";

	/** 실행마다 다른 이메일을 써서 이전 실행이 남긴 키·사용자와 섞이지 않게 한다. */
	private final String email = "it-" + UUID.randomUUID() + "@example.com";

	@Autowired
	MockMvc mockMvc;
	@Autowired
	UserRepository userRepository;
	@Autowired
	PasswordEncoder passwordEncoder;
	@Autowired
	RecordingMailSender mailSender;
	@Autowired
	RedisIndexedSessionRepository sessionRepository;
	@Autowired
	StringRedisTemplate redis;
	@MockitoSpyBean
	LoginService loginService;

	private long userId;

	@BeforeEach
	void setUp() {
		mailSender.clear();
		userId = userRepository.saveAndFlush(new User(email, "정수", passwordEncoder.encode(OLD_PASSWORD))).getId();
	}

	@AfterEach
	void cleanUp() {
		// 남은 세션(있다면), 이 사용자의 재설정 키, 재요청 제한 키, 사용자 행만 지운다. DB·테이블은 건드리지 않는다.
		sessionRepository.findByPrincipalName(String.valueOf(userId)).keySet().forEach(sessionRepository::deleteById);
		redis.delete(List.of("auth:reset-user:" + userId,
				"auth:mail-throttle:" + AuthTokenStore.PURPOSE_RESET + ":" + TokenCodec.sha256Hex(email)));
		userRepository.deleteById(userId);
	}

	private ResultActions postJson(String path, String body) throws Exception {
		return mockMvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body)
				.cookie(new Cookie("XSRF-TOKEN", "t0ken")).header("X-XSRF-TOKEN", "t0ken"));
	}

	private ResultActions login(String password) throws Exception {
		return postJson("/api/auth/login", "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}");
	}

	private void completeReset() throws Exception {
		postJson("/api/auth/password-reset-requests", "{\"email\":\"" + email + "\"}").andExpect(status().isOk());
		String link = mailSender.lastReset().link();
		String token = link.substring(link.indexOf("#token=") + "#token=".length());
		postJson("/api/auth/password-reset", "{\"token\":\"" + token + "\",\"newPassword\":\"" + NEW_PASSWORD
				+ "\",\"newPasswordConfirm\":\"" + NEW_PASSWORD + "\"}").andExpect(status().isOk());
	}

	private void assertNoLiveSessionCookie(MvcResult result) {
		List<String> sessionCookies = result.getResponse().getHeaders(HttpHeaders.SET_COOKIE).stream()
				.filter(h -> h.startsWith("SESSION=")).toList();
		assertThat(sessionCookies).allMatch(h -> h.contains("Max-Age=0"));
	}

	@Test
	void resetCompletedBetweenPasswordCheckAndSessionSaveLeavesNoSessionInRedis() throws Exception {
		CountDownLatch reached = new CountDownLatch(1);
		CountDownLatch proceed = new CountDownLatch(1);
		AtomicBoolean first = new AtomicBoolean();
		// 진짜 authenticate(이전 비밀번호 확인)를 실행한 뒤, 세션을 만들기 전에 로그인 스레드를 멈춘다.
		doAnswer(invocation -> {
			Object user = invocation.callRealMethod();
			if (first.compareAndSet(false, true)) {
				reached.countDown();
				proceed.await(20, TimeUnit.SECONDS);
			}
			return user;
		}).when(loginService).authenticate(any(), any());

		ExecutorService pool = Executors.newSingleThreadExecutor();
		try {
			Future<MvcResult> loginResult = pool.submit(() -> login(OLD_PASSWORD).andReturn());
			assertThat(reached.await(20, TimeUnit.SECONDS)).isTrue();

			completeReset(); // 로그인이 멈춘 사이 재설정이 비밀번호를 바꾸고 끝난다.
			proceed.countDown();
			MvcResult result = loginResult.get(20, TimeUnit.SECONDS);

			assertThat(result.getResponse().getStatus()).isEqualTo(401);
			assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
					.contains("AUTH_INVALID_CREDENTIALS");
			assertThat(sessionRepository.findByPrincipalName(String.valueOf(userId))).isEmpty();
			assertNoLiveSessionCookie(result);
			// 새 비밀번호로는 정상 로그인된다(실패 원인이 계정 상태가 아님).
			login(NEW_PASSWORD).andExpect(status().isOk());
		}
		finally {
			proceed.countDown();
			pool.shutdownNow();
		}
	}

	@Test
	void resetAfterLoginFinishedDeletesThatSessionInRedis() throws Exception {
		MvcResult ok = login(OLD_PASSWORD).andExpect(status().isOk()).andReturn();
		assertThat(ok.getResponse().getHeaders(HttpHeaders.SET_COOKIE)).anyMatch(h -> h.startsWith("SESSION="));
		assertThat(sessionRepository.findByPrincipalName(String.valueOf(userId))).hasSize(1);

		completeReset();

		assertThat(sessionRepository.findByPrincipalName(String.valueOf(userId))).isEmpty();
	}
}
