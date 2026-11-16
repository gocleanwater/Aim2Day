package com.aim2day.goal.goals.yearly;

import static com.aim2day.goal.support.GoalTestSupport.authed;
import static com.aim2day.goal.support.GoalTestSupport.idOf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aim2day.goal.support.GoalTestSupport;
import com.aim2day.goal.support.MutableClock;
import com.aim2day.goal.support.TestClockConfig;
import com.aim2day.goal.support.TestSessionConfig;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
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
import org.springframework.session.MapSessionRepository;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 사전 version 비교를 통과한 뒤 다른 커넥션(= 다른 인스턴스)이 먼저 커밋하는 경합.
 * SettingsConflictTests와 같은 방식으로 저장소 호출 직후에 훅을 끼워 sleep 없이 결정적으로 재현한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestSessionConfig.class, TestClockConfig.class, YearlyGoalConflictTests.HookConfig.class})
class YearlyGoalConflictTests {

	/** 목표 조회가 실제 결과를 돌려준 직후 호출되는 훅. 테스트가 설정한다. */
	static volatile Consumer<String> afterOwnedFind;

	@TestConfiguration
	static class HookConfig {
		@Bean
		static BeanPostProcessor yearlyFindHookPostProcessor() {
			return new BeanPostProcessor() {
				@Override
				public Object postProcessAfterInitialization(Object bean, String beanName) {
					if (!(bean instanceof YearlyGoalRepository)) {
						return bean;
					}
					return Proxy.newProxyInstance(getClass().getClassLoader(),
							new Class<?>[] {YearlyGoalRepository.class}, (proxy, method, args) -> {
								Object result;
								try {
									result = method.invoke(bean, args);
								} catch (InvocationTargetException e) {
									throw e.getCause();
								}
								Consumer<String> hook = afterOwnedFind;
								if (hook != null && method.getName().equals("findByIdAndUserIdAndDeletedAtIsNull")) {
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
	MutableClock clock;

	@BeforeEach
	void reset() {
		afterOwnedFind = null;
		clock.reset();
		GoalTestSupport.clean(jdbc);
	}

	@AfterEach
	void clearHook() {
		afterOwnedFind = null;
	}

	/**
	 * PATCH의 조회(1번째 findOwned) 직후 다른 스레드가 version을 올린다. 요청은 version 0을 보냈고 조회한
	 * 엔티티도 version 0이라 사전 비교는 통과하지만 UPDATE ... WHERE version = 0이 충돌한다.
	 * 이때도 409의 currentVersion은 DB의 최신 값(1)이어야 한다. 요청들이 서로 다른 값(제목)을 보내야
	 * Hibernate가 UPDATE를 생략하지 않고 충돌이 실제로 난다.
	 */
	@Test
	void concurrentPatchAfterGuardReturnsCurrentVersion() throws Exception {
		long id = idOf(mockMvc.perform(authed(sessionRepository, post("/api/goals/yearly"), 1L)
				.contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"원래\"}")).andReturn());
		AtomicInteger calls = new AtomicInteger();
		ExecutorService other = Executors.newSingleThreadExecutor();
		try {
			afterOwnedFind = name -> {
				if (calls.incrementAndGet() == 1) {
					try {
						// 요청 스레드의 트랜잭션과 커넥션을 공유하지 않도록 다른 스레드에서 커밋한다.
						other.submit(() -> jdbc.update(
								"UPDATE yearly_goals SET title = '다른 인스턴스', version = version + 1 WHERE id = ?", id)).get();
					} catch (Exception e) {
						throw new IllegalStateException(e);
					}
				}
			};
			mockMvc.perform(authed(sessionRepository, patch("/api/goals/yearly/" + id), 1L)
							.contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"내 제목\",\"version\":0}"))
					.andExpect(status().isConflict())
					.andExpect(jsonPath("$.code").value("VERSION_CONFLICT"))
					.andExpect(jsonPath("$.details.currentVersion").value(1));
		} finally {
			afterOwnedFind = null;
			other.shutdownNow();
		}
	}
}
