package com.aim2day.goal.goals.criteria;

import static com.aim2day.goal.support.GoalTestSupport.authed;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aim2day.goal.goals.criteria.dto.CriteriaDtos.ConfirmCriteriaRequest;
import com.aim2day.goal.goals.criteria.dto.CriteriaDtos.CriteriaResponse;
import com.aim2day.goal.goals.criteria.dto.CriteriaDtos.SaveCriteriaRequest;
import com.aim2day.goal.goals.yearly.YearlyGoalRepository;
import com.aim2day.goal.goals.yearly.YearlyGoalService;
import com.aim2day.goal.goals.yearly.dto.YearlyGoalDtos.CreateYearlyGoalRequest;
import com.aim2day.goal.support.GoalTestSupport;
import com.aim2day.goal.support.TestClockConfig;
import com.aim2day.goal.support.TestSessionConfig;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.session.MapSessionRepository;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 목표 행을 읽은 직후 다른 커넥션(= 다른 인스턴스)이 기준을 확정해도, 한 응답은 한 시점의 상태여야 한다.
 * 목표와 기준을 따로 읽으면 state=NOT_CONFIGURED, 옛 goalVersion인데 항목은 달성된 혼합 응답이 나온다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestSessionConfig.class, TestClockConfig.class, SnapshotReadTests.HookConfig.class})
class SnapshotReadTests {

	static volatile Consumer<String> afterGoalFind;

	@TestConfiguration
	static class HookConfig {
		@Bean
		static BeanPostProcessor goalFindHookPostProcessor() {
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
								Consumer<String> hook = afterGoalFind;
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
	YearlyGoalService goalService;
	@Autowired
	CriteriaService criteriaService;

	@BeforeEach
	void reset() {
		afterGoalFind = null;
		GoalTestSupport.clean(jdbc);
	}

	@AfterEach
	void clearHook() {
		afterGoalFind = null;
	}

	/** 40·10·50 초안(goalVersion 1)을 만들고, 훅이 한 번만 다른 스레드에서 확정(B 달성)하도록 건다. */
	private long draftWithConfirmHook(ExecutorService other) {
		long goal = goalService.create(1L, new CreateYearlyGoalRequest(null, "목표", null, null)).id();
		CriteriaResponse draft = criteriaService.save(1L, goal, new SaveCriteriaRequest(0L, List.of(
				new SaveCriteriaRequest.Item(null, "A", 40), new SaveCriteriaRequest.Item(null, "B", 10),
				new SaveCriteriaRequest.Item(null, "C", 50)), null));
		long b = draft.items().get(1).id();
		AtomicBoolean fired = new AtomicBoolean();
		afterGoalFind = name -> {
			if (fired.compareAndSet(false, true)) {
				try {
					// 요청 스레드와 다른 커넥션에서 확정을 커밋한다.
					other.submit(() -> criteriaService.confirm(1L, goal, new ConfirmCriteriaRequest(1L, List.of(b))))
							.get();
				} catch (Exception e) {
					throw new IllegalStateException(e);
				}
			}
		};
		return goal;
	}

	@Test
	void criteriaReadIsSingleSnapshot() throws Exception {
		ExecutorService other = Executors.newSingleThreadExecutor();
		try {
			long goal = draftWithConfirmHook(other);
			mockMvc.perform(authed(sessionRepository, get("/api/goals/yearly/" + goal + "/criteria"), 1L))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.data.state").value("NOT_CONFIGURED"))
					.andExpect(jsonPath("$.data.goalVersion").value(1))
					.andExpect(jsonPath("$.data.items[0].isBaselineAchieved").value(false))
					.andExpect(jsonPath("$.data.items[1].isBaselineAchieved").value(false))
					.andExpect(jsonPath("$.data.items[2].isBaselineAchieved").value(false))
					.andExpect(jsonPath("$.data.items[0].isAchieved").value(false))
					.andExpect(jsonPath("$.data.items[1].isAchieved").value(false))
					.andExpect(jsonPath("$.data.items[2].isAchieved").value(false));
			// 확정은 실제로 커밋됐다(다음 조회는 새 상태).
			afterGoalFind = null;
			mockMvc.perform(authed(sessionRepository, get("/api/goals/yearly/" + goal + "/criteria"), 1L))
					.andExpect(jsonPath("$.data.state").value("CONFIRMED"))
					.andExpect(jsonPath("$.data.items[1].isBaselineAchieved").value(true));
		} finally {
			other.shutdownNow();
		}
	}

	@Test
	void yearlyDetailIsSingleSnapshot() throws Exception {
		ExecutorService other = Executors.newSingleThreadExecutor();
		try {
			long goal = draftWithConfirmHook(other);
			mockMvc.perform(authed(sessionRepository, get("/api/goals/yearly/" + goal), 1L))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.data.goal.criteria.state").value("NOT_CONFIGURED"))
					.andExpect(jsonPath("$.data.goal.criteria.achievementRate").doesNotExist())
					.andExpect(jsonPath("$.data.goal.version").value(1));
			afterGoalFind = null;
			mockMvc.perform(authed(sessionRepository, get("/api/goals/yearly/" + goal), 1L))
					.andExpect(jsonPath("$.data.goal.criteria.state").value("CONFIRMED"))
					.andExpect(jsonPath("$.data.goal.criteria.achievementRate").value(10));
		} finally {
			other.shutdownNow();
		}
	}
}
