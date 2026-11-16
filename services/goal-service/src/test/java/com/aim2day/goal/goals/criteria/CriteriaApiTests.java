package com.aim2day.goal.goals.criteria;

import static com.aim2day.goal.support.GoalTestSupport.authed;
import static com.aim2day.goal.support.GoalTestSupport.idOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aim2day.goal.support.GoalTestSupport;
import com.aim2day.goal.support.MutableClock;
import com.aim2day.goal.support.TestClockConfig;
import com.aim2day.goal.support.TestSessionConfig;
import java.math.BigDecimal;
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
class CriteriaApiTests {

	static final String ITEMS_40_10_50 = "[{\"text\":\"A\",\"weight\":40},{\"text\":\"B\",\"weight\":10},"
			+ "{\"text\":\"C\",\"weight\":50}]";

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

	private long createGoal(long userId) throws Exception {
		return idOf(mockMvc.perform(json(post("/api/goals/yearly"), userId, "{\"title\":\"목표\"}")).andReturn());
	}

	private MvcResult putCriteria(long userId, long goalId, String body) throws Exception {
		return mockMvc.perform(json(put("/api/goals/yearly/" + goalId + "/criteria"), userId, body)).andReturn();
	}

	private long criterionId(MvcResult result, int index) throws Exception {
		return ((Number) GoalTestSupport.read(result, "$.data.items[" + index + "].id")).longValue();
	}

	/** 40·10·50 초안을 저장해 목표 version을 0에서 1로 올린다. */
	private MvcResult saveDraft(long userId, long goalId) throws Exception {
		MvcResult result = putCriteria(userId, goalId, "{\"goalVersion\":0,\"items\":" + ITEMS_40_10_50 + "}");
		assertThat(result.getResponse().getStatus()).isEqualTo(200);
		return result;
	}

	private List<Map<String, Object>> logs(long goalId) {
		return jdbc.queryForList("SELECT * FROM goal_progress_logs WHERE target_id = ? ORDER BY id", goalId);
	}

	@Test
	void getBeforeConfigIsNotConfiguredAndEmpty() throws Exception {
		long goal = createGoal(1L);
		mockMvc.perform(authed(sessionRepository, get("/api/goals/yearly/" + goal + "/criteria"), 1L))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.state").value("NOT_CONFIGURED"))
				.andExpect(jsonPath("$.data.achievementRate").doesNotExist())
				.andExpect(jsonPath("$.data.initialBaselineRate").doesNotExist())
				.andExpect(jsonPath("$.data.items").isEmpty())
				.andExpect(jsonPath("$.data.totalWeight").value(0))
				.andExpect(jsonPath("$.data.goalVersion").value(0));
	}

	@Test
	void draftSaveSkipsSumCheckAndBumpsGoalVersion() throws Exception {
		long goal = createGoal(1L);
		mockMvc.perform(json(put("/api/goals/yearly/" + goal + "/criteria"), 1L,
						"{\"goalVersion\":0,\"items\":[{\"text\":\"A\",\"weight\":30},{\"text\":\"B\",\"weight\":30}]}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.state").value("NOT_CONFIGURED"))
				.andExpect(jsonPath("$.data.totalWeight").value(60))
				.andExpect(jsonPath("$.data.goalVersion").value(1))
				.andExpect(jsonPath("$.data.items[0].text").value("A"))
				.andExpect(jsonPath("$.data.items[0].isAchieved").value(false))
				.andExpect(jsonPath("$.data.items[0].isBaselineAchieved").value(false));
		// 목표 응답에도 같은 version이 보인다.
		assertThat(jdbc.queryForObject("SELECT version FROM yearly_goals WHERE id = ?", Long.class, goal)).isEqualTo(1L);
		// 초안은 진행 기록을 남기지 않는다.
		assertThat(logs(goal)).isEmpty();
	}

	@Test
	void draftReplaceUpdatesAddsAndDeletes() throws Exception {
		long goal = createGoal(1L);
		MvcResult first = saveDraft(1L, goal);
		long a = criterionId(first, 0);
		long b = criterionId(first, 1);
		// A는 수정, B는 목록에서 빠져 삭제, 새 항목 D 추가
		MvcResult second = putCriteria(1L, goal, "{\"goalVersion\":1,\"items\":["
				+ "{\"id\":" + a + ",\"text\":\"A 수정\",\"weight\":60},{\"text\":\"D\",\"weight\":40}]}");
		assertThat(second.getResponse().getStatus()).isEqualTo(200);
		assertThat((String) GoalTestSupport.read(second, "$.data.items[0].text")).isEqualTo("A 수정");
		assertThat(criterionId(second, 0)).isEqualTo(a);
		assertThat((String) GoalTestSupport.read(second, "$.data.items[1].text")).isEqualTo("D");
		assertThat(((Number) GoalTestSupport.read(second, "$.data.totalWeight")).intValue()).isEqualTo(100);
		assertThat(((Number) GoalTestSupport.read(second, "$.data.goalVersion")).longValue()).isEqualTo(2L);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM yearly_goal_criteria WHERE id = ?", Integer.class, b))
				.isZero();
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM yearly_goal_criteria WHERE yearly_goal_id = ?",
				Integer.class, goal)).isEqualTo(2);
	}

	@Test
	void foreignOrDuplicateCriterionIdIs400() throws Exception {
		long goal = createGoal(1L);
		long otherGoal = createGoal(2L);
		MvcResult mine = saveDraft(1L, goal);
		MvcResult theirs = saveDraft(2L, otherGoal);
		long myId = criterionId(mine, 0);
		long theirId = criterionId(theirs, 0);
		// 남의 목표의 기준 id
		mockMvc.perform(json(put("/api/goals/yearly/" + goal + "/criteria"), 1L,
						"{\"goalVersion\":1,\"items\":[{\"id\":" + theirId + ",\"text\":\"x\",\"weight\":100}]}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
				.andExpect(jsonPath("$.details.fieldErrors[0].field").value("items[0].id"))
				.andExpect(jsonPath("$.details.fieldErrors[0].reason").value("INVALID_FORMAT"));
		// 같은 id 두 번
		mockMvc.perform(json(put("/api/goals/yearly/" + goal + "/criteria"), 1L,
						"{\"goalVersion\":1,\"items\":[{\"id\":" + myId + ",\"text\":\"x\",\"weight\":50},"
								+ "{\"id\":" + myId + ",\"text\":\"y\",\"weight\":50}]}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.details.fieldErrors[0].field").value("items[1].id"));
		// 남의 기준은 그대로다.
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM yearly_goal_criteria WHERE yearly_goal_id = ?",
				Integer.class, otherGoal)).isEqualTo(3);
	}

	@Test
	void itemsCountAndRangeValidated() throws Exception {
		long goal = createGoal(1L);
		String path = "/api/goals/yearly/" + goal + "/criteria";
		mockMvc.perform(json(put(path), 1L, "{\"goalVersion\":0,\"items\":[]}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.details.fieldErrors[0].field").value("items"))
				.andExpect(jsonPath("$.details.fieldErrors[0].reason").value("OUT_OF_RANGE"));
		StringBuilder twentyOne = new StringBuilder("[");
		for (int i = 0; i < 21; i++) {
			twentyOne.append(i > 0 ? "," : "").append("{\"text\":\"t\",\"weight\":1}");
		}
		twentyOne.append("]");
		mockMvc.perform(json(put(path), 1L, "{\"goalVersion\":0,\"items\":" + twentyOne + "}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.details.fieldErrors[0].field").value("items"));
		for (int weight : new int[] {0, 101}) {
			mockMvc.perform(json(put(path), 1L,
							"{\"goalVersion\":0,\"items\":[{\"text\":\"t\",\"weight\":" + weight + "}]}"))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.details.fieldErrors[0].field").value("items[0].weight"))
					.andExpect(jsonPath("$.details.fieldErrors[0].reason").value("OUT_OF_RANGE"));
		}
		mockMvc.perform(json(put(path), 1L,
						"{\"goalVersion\":0,\"items\":[{\"text\":\"" + "a".repeat(256) + "\",\"weight\":10}]}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.details.fieldErrors[0].field").value("items[0].text"))
				.andExpect(jsonPath("$.details.fieldErrors[0].reason").value("TOO_LONG"));
		mockMvc.perform(json(put(path), 1L, "{\"goalVersion\":0,\"items\":[{\"text\":\"t\",\"weight\":null}]}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.details.fieldErrors[0].reason").value("REQUIRED"));
		mockMvc.perform(json(put(path), 1L, "{\"items\":[{\"text\":\"t\",\"weight\":10}]}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.details.fieldErrors[0].field").value("goalVersion"));
		// 경계값(20개, 배점 1과 100, 255자)은 통과한다.
		StringBuilder twenty = new StringBuilder("[");
		for (int i = 0; i < 20; i++) {
			twenty.append(i > 0 ? "," : "").append("{\"text\":\"" + "a".repeat(255) + "\",\"weight\":1}");
		}
		twenty.append("]");
		mockMvc.perform(json(put(path), 1L, "{\"goalVersion\":0,\"items\":" + twenty + "}"))
				.andExpect(status().isOk());
	}

	@Test
	void draftStaleGoalVersionIs409() throws Exception {
		long goal = createGoal(1L);
		saveDraft(1L, goal);
		mockMvc.perform(json(put("/api/goals/yearly/" + goal + "/criteria"), 1L,
						"{\"goalVersion\":0,\"items\":[{\"text\":\"x\",\"weight\":10}]}"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("VERSION_CONFLICT"))
				.andExpect(jsonPath("$.details.currentVersion").value(1));
	}

	@Test
	void confirmRequiresSum100() throws Exception {
		long goal = createGoal(1L);
		putCriteria(1L, goal, "{\"goalVersion\":0,\"items\":[{\"text\":\"A\",\"weight\":30},{\"text\":\"B\",\"weight\":30}]}");
		mockMvc.perform(json(post("/api/goals/yearly/" + goal + "/criteria/confirm"), 1L, "{\"goalVersion\":1}"))
				.andExpect(status().isUnprocessableContent())
				.andExpect(jsonPath("$.code").value("CRITERIA_WEIGHT_SUM_INVALID"))
				.andExpect(jsonPath("$.details.totalWeight").value(60))
				.andExpect(jsonPath("$.details.required").value(100));
		// 기준이 없는 목표도 합계 0이라 같은 오류다.
		long empty = createGoal(1L);
		mockMvc.perform(json(post("/api/goals/yearly/" + empty + "/criteria/confirm"), 1L, "{\"goalVersion\":0}"))
				.andExpect(status().isUnprocessableContent())
				.andExpect(jsonPath("$.details.totalWeight").value(0));
	}

	@Test
	void confirmStoresBaselineAndLogsInitialObservation() throws Exception {
		long goal = createGoal(1L);
		MvcResult draft = saveDraft(1L, goal);
		long ten = criterionId(draft, 1);
		mockMvc.perform(json(post("/api/goals/yearly/" + goal + "/criteria/confirm"), 1L,
						"{\"goalVersion\":1,\"baselineAchievedCriterionIds\":[" + ten + "]}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.state").value("CONFIRMED"))
				.andExpect(jsonPath("$.data.initialBaselineRate").value(10))
				.andExpect(jsonPath("$.data.achievementRate").value(10))
				.andExpect(jsonPath("$.data.goalVersion").value(2))
				.andExpect(jsonPath("$.data.confirmedAt").value("2026-11-12T03:00:00Z"))
				.andExpect(jsonPath("$.data.lastRevisedAt").value("2026-11-12T03:00:00Z"))
				.andExpect(jsonPath("$.data.items[1].isAchieved").value(true))
				.andExpect(jsonPath("$.data.items[1].isBaselineAchieved").value(true))
				.andExpect(jsonPath("$.data.items[1].achievedAt").value("2026-11-12T03:00:00Z"))
				.andExpect(jsonPath("$.data.items[0].isAchieved").value(false))
				.andExpect(jsonPath("$.data.items[0].isBaselineAchieved").value(false));
		List<Map<String, Object>> logs = logs(goal);
		assertThat(logs).hasSize(1);
		Map<String, Object> log = logs.get(0);
		assertThat(log.get("TARGET_TYPE")).isEqualTo("YEARLY_GOAL");
		assertThat((BigDecimal) log.get("PREVIOUS_RATE")).isEqualByComparingTo("0.00");
		assertThat((BigDecimal) log.get("NEW_RATE")).isEqualByComparingTo("10.00");
		assertThat(log.get("CHANGE_TYPE")).isEqualTo("PROGRESS_UPDATE");
		assertThat(log.get("LOG_DATE").toString()).isEqualTo("2026-11-12");
		assertThat(log.get("USER_ID")).isEqualTo(1L);
		// 목표 응답 요약도 확정 상태를 보인다.
		mockMvc.perform(authed(sessionRepository, get("/api/goals/yearly/" + goal), 1L))
				.andExpect(jsonPath("$.data.goal.criteria.state").value("CONFIRMED"))
				.andExpect(jsonPath("$.data.goal.criteria.achievementRate").value(10))
				.andExpect(jsonPath("$.data.goal.criteria.initialBaselineRate").value(10));
	}

	@Test
	void confirmWithEmptyBaselineIsZeroPercent() throws Exception {
		long goal = createGoal(1L);
		saveDraft(1L, goal);
		mockMvc.perform(json(post("/api/goals/yearly/" + goal + "/criteria/confirm"), 1L,
						"{\"goalVersion\":1,\"baselineAchievedCriterionIds\":[]}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.initialBaselineRate").value(0))
				.andExpect(jsonPath("$.data.achievementRate").value(0));
		assertThat((BigDecimal) logs(goal).get(0).get("NEW_RATE")).isEqualByComparingTo("0.00");
	}

	@Test
	void confirmWithForeignOrDuplicateBaselineIdIs400() throws Exception {
		long goal = createGoal(1L);
		long other = createGoal(2L);
		MvcResult mine = saveDraft(1L, goal);
		long theirId = criterionId(saveDraft(2L, other), 0);
		long myId = criterionId(mine, 0);
		mockMvc.perform(json(post("/api/goals/yearly/" + goal + "/criteria/confirm"), 1L,
						"{\"goalVersion\":1,\"baselineAchievedCriterionIds\":[" + theirId + "]}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.details.fieldErrors[0].field").value("baselineAchievedCriterionIds"));
		mockMvc.perform(json(post("/api/goals/yearly/" + goal + "/criteria/confirm"), 1L,
						"{\"goalVersion\":1,\"baselineAchievedCriterionIds\":[" + myId + "," + myId + "]}"))
				.andExpect(status().isBadRequest());
		// 실패한 확정은 아무것도 바꾸지 않는다.
		assertThat(jdbc.queryForObject("SELECT is_criteria_confirmed FROM yearly_goals WHERE id = ?", Boolean.class,
				goal)).isFalse();
		assertThat(logs(goal)).isEmpty();
	}

	@Test
	void confirmTwiceIs409AlreadyConfirmed() throws Exception {
		long goal = createGoal(1L);
		saveDraft(1L, goal);
		mockMvc.perform(json(post("/api/goals/yearly/" + goal + "/criteria/confirm"), 1L, "{\"goalVersion\":1}"))
				.andExpect(status().isOk());
		mockMvc.perform(json(post("/api/goals/yearly/" + goal + "/criteria/confirm"), 1L, "{\"goalVersion\":2}"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("CRITERIA_ALREADY_CONFIRMED"));
	}

	@Test
	void confirmStaleGoalVersionIs409() throws Exception {
		long goal = createGoal(1L);
		saveDraft(1L, goal);
		mockMvc.perform(json(post("/api/goals/yearly/" + goal + "/criteria/confirm"), 1L, "{\"goalVersion\":0}"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("VERSION_CONFLICT"))
				.andExpect(jsonPath("$.details.currentVersion").value(1));
		assertThat(logs(goal)).isEmpty();
	}

	@Test
	void revisionKeepsAchievementAndAlwaysLogs() throws Exception {
		long goal = createGoal(1L);
		MvcResult draft = saveDraft(1L, goal);
		long a = criterionId(draft, 0);
		long b = criterionId(draft, 1);
		long c = criterionId(draft, 2);
		mockMvc.perform(json(post("/api/goals/yearly/" + goal + "/criteria/confirm"), 1L,
				"{\"goalVersion\":1,\"baselineAchievedCriterionIds\":[" + b + "]}")).andExpect(status().isOk());

		// 시계를 옮겨 개정 시각이 확정 시각과 달라지게 한다.
		clock.set(java.time.Instant.parse("2026-11-13T05:00:00Z"));
		// B(10, 달성)를 유지하고 A·C를 합쳐 90짜리 새 항목으로 바꾼다. 달성률은 개정 전후 모두 10이다.
		mockMvc.perform(json(put("/api/goals/yearly/" + goal + "/criteria"), 1L,
						"{\"goalVersion\":2,\"memo\":\"기준 정리\",\"items\":[{\"id\":" + b + ",\"text\":\"B\",\"weight\":10},"
								+ "{\"text\":\"새 항목\",\"weight\":90}]}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.state").value("CONFIRMED"))
				.andExpect(jsonPath("$.data.achievementRate").value(10))
				.andExpect(jsonPath("$.data.goalVersion").value(3))
				.andExpect(jsonPath("$.data.items[0].id").value(b))
				.andExpect(jsonPath("$.data.items[0].isAchieved").value(true))
				.andExpect(jsonPath("$.data.items[1].isAchieved").value(false))
				.andExpect(jsonPath("$.data.items[1].isBaselineAchieved").value(false))
				.andExpect(jsonPath("$.data.confirmedAt").value("2026-11-12T03:00:00Z"))
				.andExpect(jsonPath("$.data.lastRevisedAt").value("2026-11-13T05:00:00Z"));
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM yearly_goal_criteria WHERE id IN (?, ?)", Integer.class, a, c))
				.isZero();

		List<Map<String, Object>> logs = logs(goal);
		assertThat(logs).hasSize(2);
		Map<String, Object> revision = logs.get(1);
		assertThat(revision.get("CHANGE_TYPE")).isEqualTo("CRITERIA_REVISION");
		assertThat((BigDecimal) revision.get("PREVIOUS_RATE")).isEqualByComparingTo("10.00");
		assertThat((BigDecimal) revision.get("NEW_RATE")).isEqualByComparingTo("10.00");
		assertThat(revision.get("MEMO")).isEqualTo("기준 정리");
		assertThat(revision.get("LOG_DATE").toString()).isEqualTo("2026-11-13");
	}

	@Test
	void revisionWithSumNot100Is422() throws Exception {
		long goal = createGoal(1L);
		saveDraft(1L, goal);
		mockMvc.perform(json(post("/api/goals/yearly/" + goal + "/criteria/confirm"), 1L, "{\"goalVersion\":1}"))
				.andExpect(status().isOk());
		mockMvc.perform(json(put("/api/goals/yearly/" + goal + "/criteria"), 1L,
						"{\"goalVersion\":2,\"items\":[{\"text\":\"x\",\"weight\":90}]}"))
				.andExpect(status().isUnprocessableContent())
				.andExpect(jsonPath("$.code").value("CRITERIA_WEIGHT_SUM_INVALID"))
				.andExpect(jsonPath("$.details.totalWeight").value(90))
				.andExpect(jsonPath("$.details.required").value(100));
		// 실패한 개정은 기준도 version도 기록도 바꾸지 않는다.
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM yearly_goal_criteria WHERE yearly_goal_id = ?",
				Integer.class, goal)).isEqualTo(3);
		assertThat(jdbc.queryForObject("SELECT version FROM yearly_goals WHERE id = ?", Long.class, goal))
				.isEqualTo(2L);
		assertThat(logs(goal)).hasSize(1);
	}

	@Test
	void revisionOrderChecksValidationBeforeVersionBeforeSum() throws Exception {
		long goal = createGoal(1L);
		saveDraft(1L, goal);
		mockMvc.perform(json(post("/api/goals/yearly/" + goal + "/criteria/confirm"), 1L, "{\"goalVersion\":1}"))
				.andExpect(status().isOk());
		// 합계도 틀리고 version도 낡았다 -> 409가 먼저(400 -> 409 -> 422)
		mockMvc.perform(json(put("/api/goals/yearly/" + goal + "/criteria"), 1L,
						"{\"goalVersion\":0,\"items\":[{\"text\":\"x\",\"weight\":90}]}"))
				.andExpect(status().isConflict());
		// 형식도 틀렸다 -> 400이 먼저
		mockMvc.perform(json(put("/api/goals/yearly/" + goal + "/criteria"), 1L,
						"{\"goalVersion\":0,\"items\":[{\"text\":\"x\",\"weight\":0}]}"))
				.andExpect(status().isBadRequest());
	}

	@Test
	void otherUsersGoalIs404() throws Exception {
		long goal = createGoal(1L);
		saveDraft(1L, goal);
		String base = "/api/goals/yearly/" + goal + "/criteria";
		mockMvc.perform(authed(sessionRepository, get(base), 2L))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
				.andExpect(jsonPath("$.details.resource").value("YEARLY_GOAL"))
				.andExpect(jsonPath("$.details.id").value(goal));
		mockMvc.perform(json(put(base), 2L, "{\"goalVersion\":1,\"items\":[{\"text\":\"x\",\"weight\":100}]}"))
				.andExpect(status().isNotFound());
		mockMvc.perform(json(post(base + "/confirm"), 2L, "{\"goalVersion\":1}"))
				.andExpect(status().isNotFound());
		// 남의 요청은 아무것도 바꾸지 않았다.
		assertThat(jdbc.queryForObject("SELECT version FROM yearly_goals WHERE id = ?", Long.class, goal))
				.isEqualTo(1L);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM yearly_goal_criteria WHERE yearly_goal_id = ?",
				Integer.class, goal)).isEqualTo(3);
	}
}
