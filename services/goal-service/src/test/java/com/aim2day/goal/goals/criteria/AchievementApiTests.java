package com.aim2day.goal.goals.criteria;

import static com.aim2day.goal.support.GoalTestSupport.authed;
import static com.aim2day.goal.support.GoalTestSupport.idOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aim2day.goal.support.GoalTestSupport;
import com.aim2day.goal.support.MutableClock;
import com.aim2day.goal.support.TestClockConfig;
import com.aim2day.goal.support.TestSessionConfig;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.session.MapSessionRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest
@AutoConfigureMockMvc
@Import({TestSessionConfig.class, TestClockConfig.class})
class AchievementApiTests {

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
		clock.reset();
		GoalTestSupport.clean(jdbc);
	}

	private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder builder, long userId, String body) {
		return authed(sessionRepository, builder, userId).contentType(MediaType.APPLICATION_JSON).content(body);
	}

	/** 목표를 만들고 40(A)·10(B)·50(C) 기준을 확정한다. 기준선은 B만 달성(10). ids에 A, B, C 순서로 담긴다. */
	private long confirmedGoal(long userId, long[] ids) throws Exception {
		long goal = idOf(mockMvc.perform(json(post("/api/goals/yearly"), userId, "{\"title\":\"목표\"}")).andReturn());
		MvcResult draft = mockMvc.perform(json(put("/api/goals/yearly/" + goal + "/criteria"), userId,
				"{\"goalVersion\":0,\"items\":" + CriteriaApiTests.ITEMS_40_10_50 + "}")).andReturn();
		for (int i = 0; i < 3; i++) {
			ids[i] = ((Number) GoalTestSupport.read(draft, "$.data.items[" + i + "].id")).longValue();
		}
		mockMvc.perform(json(post("/api/goals/yearly/" + goal + "/criteria/confirm"), userId,
				"{\"goalVersion\":1,\"baselineAchievedCriterionIds\":[" + ids[1] + "]}")).andExpect(status().isOk());
		return goal;
	}

	private MockHttpServletRequestBuilder achievement(long userId, long goal, long criterion, String body) {
		return json(put("/api/goals/yearly/" + goal + "/criteria/" + criterion + "/achievement"), userId, body);
	}

	private List<Map<String, Object>> logs(long goalId) {
		return jdbc.queryForList("SELECT * FROM goal_progress_logs WHERE target_id = ? ORDER BY id", goalId);
	}

	@Test
	void achieveAddsWeightAndLogs() throws Exception {
		long[] ids = new long[3];
		long goal = confirmedGoal(1L, ids);
		mockMvc.perform(achievement(1L, goal, ids[0], "{\"achieved\":true,\"memo\":\"첫 프로젝트 배포 완료\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.criterionId").value(ids[0]))
				.andExpect(jsonPath("$.data.isAchieved").value(true))
				.andExpect(jsonPath("$.data.achievementRate").value(50));
		List<Map<String, Object>> logs = logs(goal);
		assertThat(logs).hasSize(2);
		Map<String, Object> log = logs.get(1);
		assertThat(log.get("CHANGE_TYPE")).isEqualTo("PROGRESS_UPDATE");
		assertThat((BigDecimal) log.get("PREVIOUS_RATE")).isEqualByComparingTo("10.00");
		assertThat((BigDecimal) log.get("NEW_RATE")).isEqualByComparingTo("50.00");
		assertThat(log.get("MEMO")).isEqualTo("첫 프로젝트 배포 완료");
		assertThat(log.get("LOG_DATE").toString()).isEqualTo("2026-11-12");
		assertThat(jdbc.queryForObject("SELECT is_achieved FROM yearly_goal_criteria WHERE id = ?", Boolean.class,
				ids[0])).isTrue();
	}

	@Test
	void sameAchievementIsNoOp() throws Exception {
		long[] ids = new long[3];
		long goal = confirmedGoal(1L, ids);
		mockMvc.perform(achievement(1L, goal, ids[0], "{\"achieved\":true}")).andExpect(status().isOk());
		Timestamp achievedAt = jdbc.queryForObject("SELECT achieved_at FROM yearly_goal_criteria WHERE id = ?",
				Timestamp.class, ids[0]);
		int logCount = logs(goal).size();
		// 시각이 달라진 뒤에 같은 값을 보내도 achievedAt이 바뀌지 않는다.
		clock.set(Instant.parse("2026-11-14T00:00:00Z"));
		mockMvc.perform(achievement(1L, goal, ids[0], "{\"achieved\":true,\"memo\":\"다시\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.isAchieved").value(true))
				.andExpect(jsonPath("$.data.achievementRate").value(50));
		assertThat(logs(goal)).hasSize(logCount);
		assertThat(jdbc.queryForObject("SELECT achieved_at FROM yearly_goal_criteria WHERE id = ?", Timestamp.class,
				ids[0])).isEqualTo(achievedAt);
		// 미달성 기준에 achieved:false도 변화 없음이다.
		mockMvc.perform(achievement(1L, goal, ids[2], "{\"achieved\":false}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.achievementRate").value(50));
		assertThat(logs(goal)).hasSize(logCount);
	}

	@Test
	void unachieveSubtractsAndClearsAchievedAt() throws Exception {
		long[] ids = new long[3];
		long goal = confirmedGoal(1L, ids);
		mockMvc.perform(achievement(1L, goal, ids[1], "{\"achieved\":false,\"memo\":\"취소\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.isAchieved").value(false))
				.andExpect(jsonPath("$.data.achievementRate").value(0));
		assertThat(jdbc.queryForObject("SELECT achieved_at FROM yearly_goal_criteria WHERE id = ?", Timestamp.class,
				ids[1])).isNull();
		Map<String, Object> log = logs(goal).get(1);
		assertThat((BigDecimal) log.get("PREVIOUS_RATE")).isEqualByComparingTo("10.00");
		assertThat((BigDecimal) log.get("NEW_RATE")).isEqualByComparingTo("0.00");
	}

	@Test
	void achievementDoesNotBumpGoalVersion() throws Exception {
		long[] ids = new long[3];
		long goal = confirmedGoal(1L, ids);
		Long before = jdbc.queryForObject("SELECT version FROM yearly_goals WHERE id = ?", Long.class, goal);
		mockMvc.perform(achievement(1L, goal, ids[0], "{\"achieved\":true}")).andExpect(status().isOk());
		mockMvc.perform(achievement(1L, goal, ids[0], "{\"achieved\":false}")).andExpect(status().isOk());
		assertThat(jdbc.queryForObject("SELECT version FROM yearly_goals WHERE id = ?", Long.class, goal))
				.isEqualTo(before);
	}

	@Test
	void achievementBeforeConfirmIs422() throws Exception {
		long goal = idOf(mockMvc.perform(json(post("/api/goals/yearly"), 1L, "{\"title\":\"목표\"}")).andReturn());
		MvcResult draft = mockMvc.perform(json(put("/api/goals/yearly/" + goal + "/criteria"), 1L,
				"{\"goalVersion\":0,\"items\":" + CriteriaApiTests.ITEMS_40_10_50 + "}")).andReturn();
		long criterion = ((Number) GoalTestSupport.read(draft, "$.data.items[0].id")).longValue();
		mockMvc.perform(achievement(1L, goal, criterion, "{\"achieved\":true}"))
				.andExpect(status().isUnprocessableContent())
				.andExpect(jsonPath("$.code").value("CRITERIA_NOT_CONFIRMED"));
		assertThat(logs(goal)).isEmpty();
	}

	@Test
	void criterionOfOtherGoalIs404() throws Exception {
		long[] mine = new long[3];
		long[] other = new long[3];
		long goal = confirmedGoal(1L, mine);
		long otherUsersGoal = confirmedGoal(2L, other);
		long myOtherGoal = idOf(mockMvc.perform(json(post("/api/goals/yearly"), 1L, "{\"title\":\"둘째\"}")).andReturn());
		// 남의 목표 id로 접근: 목표가 404(YEARLY_GOAL)
		mockMvc.perform(achievement(1L, otherUsersGoal, other[0], "{\"achieved\":true}"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.details.resource").value("YEARLY_GOAL"));
		// 내 목표 id에 남의 기준 id: 기준이 404(CRITERION)
		mockMvc.perform(achievement(1L, goal, other[0], "{\"achieved\":true}"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
				.andExpect(jsonPath("$.details.resource").value("CRITERION"))
				.andExpect(jsonPath("$.details.id").value(other[0]));
		// 내 다른 목표에 이 목표의 기준 id
		mockMvc.perform(achievement(1L, myOtherGoal, mine[0], "{\"achieved\":true}"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.details.resource").value("CRITERION"));
		// 존재하지 않는 기준 id
		mockMvc.perform(achievement(1L, goal, 999999L, "{\"achieved\":true}"))
				.andExpect(status().isNotFound());
		// 기준선 외에는 아무것도 달성 처리되지 않았다.
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM yearly_goal_criteria WHERE is_achieved = TRUE AND "
				+ "is_baseline_achieved = FALSE", Integer.class)).isZero();
	}

	@Test
	void memoOver255Is400() throws Exception {
		long[] ids = new long[3];
		long goal = confirmedGoal(1L, ids);
		mockMvc.perform(achievement(1L, goal, ids[0], "{\"achieved\":true,\"memo\":\"" + "a".repeat(256) + "\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
				.andExpect(jsonPath("$.details.fieldErrors[0].field").value("memo"))
				.andExpect(jsonPath("$.details.fieldErrors[0].reason").value("TOO_LONG"));
		mockMvc.perform(achievement(1L, goal, ids[0], "{\"memo\":\"x\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.details.fieldErrors[0].field").value("achieved"))
				.andExpect(jsonPath("$.details.fieldErrors[0].reason").value("REQUIRED"));
		mockMvc.perform(achievement(1L, goal, ids[0], "{\"achieved\":true,\"memo\":\"" + "a".repeat(255) + "\"}"))
				.andExpect(status().isOk());
	}
}
