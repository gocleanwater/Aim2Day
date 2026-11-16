package com.aim2day.goal.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aim2day.goal.support.TestSessionConfig;
import jakarta.servlet.http.Cookie;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.session.MapSession;
import org.springframework.session.MapSessionRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@AutoConfigureMockMvc
@Import({TestSessionConfig.class, SettingsConflictTests.HookConfig.class})
class SettingsConflictTests {

	private static final String TOKEN = "csrf-test-token";

	/** findByUserId가 실제 결과를 돌려준 직후 호출되는 훅. 테스트가 설정한다. */
	static volatile Consumer<String> afterFindByUserId;

	/**
	 * Spring Data 저장소 프록시는 Mockito spy로 감쌀 수 없어(추상 메서드 실제 호출 불가)
	 * 같은 인터페이스의 JDK 프록시로 한 번 더 감싸 findByUserId 직후에 훅을 끼운다.
	 */
	@TestConfiguration
	static class HookConfig {
		@Bean
		static BeanPostProcessor findHookPostProcessor() {
			return new BeanPostProcessor() {
				@Override
				public Object postProcessAfterInitialization(Object bean, String beanName) {
					if (!(bean instanceof UserSettingRepository)) {
						return bean;
					}
					return Proxy.newProxyInstance(getClass().getClassLoader(),
							new Class<?>[] {UserSettingRepository.class}, (proxy, method, args) -> {
								Object result;
								try {
									result = method.invoke(bean, args);
								} catch (InvocationTargetException e) {
									throw e.getCause();
								}
								Consumer<String> hook = afterFindByUserId;
								if (hook != null && method.getName().equals("findByUserId")) {
									hook.accept(method.getName());
								}
								return result;
							});
				}
			};
		}
	}

	@Autowired
	MockMvc mockMvc;
	@Autowired
	MapSessionRepository sessionRepository;
	@Autowired
	JdbcTemplate jdbc;
	@Autowired
	SettingsService service;
	@Autowired
	PlatformTransactionManager txManager;
	@Autowired
	UserSettingRepository repository;

	@BeforeEach
	void reset() {
		afterFindByUserId = null;
		jdbc.update("DELETE FROM user_settings");
		jdbc.update("MERGE INTO users (id) KEY (id) VALUES (?)", 9L);
	}

	@AfterEach
	void clearHook() {
		afterFindByUserId = null;
	}

	/**
	 * 사전 비교를 통과한 직후 다른 커넥션이 version을 올려 saveAndFlush를 충돌시킨다(sleep 없이 결정적).
	 * update()의 findByUserId 호출 순서: 1) getOrCreate 안의 조회, 2) 트랜잭션 안의 조회, 3) currentVersion 재조회.
	 * 2번 직후에 올리므로 guard는 통과하고 flush에서 충돌한다. 응답은 3번이 읽은 DB 값을 담아야 한다.
	 */
	@Test
	void concurrentPutAfterGuardReturnsCurrentVersion() throws Exception {
		service.getOrCreate(9L);
		AtomicInteger counter = new AtomicInteger();
		ExecutorService bump = Executors.newSingleThreadExecutor();
		try {
			afterFindByUserId = name -> {
				if (counter.incrementAndGet() == 2) {
					// 요청 스레드에서 실행하면 진행 중인 트랜잭션의 커넥션을 공유하므로 다른 스레드(별도 커넥션)에서 커밋한다.
					try {
						bump.submit(() -> jdbc.update(
								"UPDATE user_settings SET version = version + 1 WHERE user_id = ?", 9L)).get();
					} catch (Exception e) {
						throw new IllegalStateException(e);
					}
				}
			};
			MapSession session = sessionRepository.createSession();
			session.setAttribute("userId", 9L);
			sessionRepository.save(session);
			String sid = Base64.getEncoder().encodeToString(session.getId().getBytes(StandardCharsets.UTF_8));
			mockMvc.perform(put("/api/settings")
							.cookie(new Cookie("SESSION", sid), new Cookie("XSRF-TOKEN", TOKEN))
							.header("X-XSRF-TOKEN", TOKEN).contentType(MediaType.APPLICATION_JSON)
							.content("{\"weekStartDay\":\"MONDAY\",\"timezone\":\"Asia/Seoul\",\"version\":0}"))
					.andExpect(status().isConflict())
					.andExpect(jsonPath("$.code").value("VERSION_CONFLICT"))
					.andExpect(jsonPath("$.details.currentVersion").value(1));
		} finally {
			afterFindByUserId = null;
			bump.shutdownNow();
		}
	}

	/**
	 * 첫 생성 경합의 복구 경로(유니크 위반 -> 재조회)를 실제로 타게 하는 테스트.
	 * getOrCreate의 첫 findByUserId가 "없음"을 돌려준 직후 다른 스레드(별도 커넥션)가 같은 사용자 행을
	 * 커밋하게 한다. 그러면 이어지는 INSERT가 user_id 유니크 제약에 걸리고, 서비스는 새 트랜잭션에서
	 * 다시 조회해 그 행을 돌려줘야 한다. 바깥 트랜잭션 안에서 호출해도 같아야 한다.
	 * (H2는 READ COMMITTED라 MySQL REPEATABLE READ 스냅샷은 재현하지 못하지만, 복구 분기 자체는 검증된다.)
	 */
	@Test
	void concurrentFirstReadInsideOuterTransactionRecovers() throws Exception {
		ExecutorService pool = Executors.newSingleThreadExecutor();
		AtomicInteger findCalls = new AtomicInteger();
		AtomicLong otherThreadRowId = new AtomicLong();
		try {
			afterFindByUserId = name -> {
				// 첫 조회(결과 없음) 직후에만 다른 스레드가 같은 행을 만든다.
				if (findCalls.incrementAndGet() == 1) {
					try {
						otherThreadRowId.set(pool.submit(() -> {
							jdbc.update("INSERT INTO user_settings (user_id) VALUES (?)", 9L);
							return jdbc.queryForObject("SELECT id FROM user_settings WHERE user_id = ?", Long.class, 9L);
						}).get());
					} catch (Exception e) {
						throw new IllegalStateException(e);
					}
				}
			};
			UserSetting result = new TransactionTemplate(txManager).execute(status -> service.getOrCreate(9L));
			afterFindByUserId = null;
			assertThat(result.getId()).isEqualTo(otherThreadRowId.get());
			assertThat(repository.count()).isEqualTo(1);
			// 첫 조회(없음) + 유니크 위반 뒤 재조회 = 정확히 2회. 재조회 경로를 타지 않았다면 1회다.
			assertThat(findCalls.get()).isEqualTo(2);
		} finally {
			afterFindByUserId = null;
			pool.shutdownNow();
		}
	}
}
