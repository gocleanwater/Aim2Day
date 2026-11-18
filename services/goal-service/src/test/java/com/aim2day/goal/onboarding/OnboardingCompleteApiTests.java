package com.aim2day.goal.onboarding;

import static org.assertj.core.api.Assertions.assertThat;

import com.aim2day.goal.support.GoalTestSupport;
import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/** POST /api/goals/onboarding/complete. 시계는 2026-11-12(목), 이번 주 11-08~11-14. */
class OnboardingCompleteApiTests extends OnboardingTestBase {

	private static final String BASIC = "{\"yearlyGoals\":[{\"tempId\":\"y1\",\"title\":\"연간\"}],"
			+ "\"monthlyGoals\":[{\"tempId\":\"m1\",\"yearlyGoalTempId\":\"y1\",\"title\":\"월간\"}],"
			+ "\"weeklyGoals\":[{\"tempId\":\"w1\",\"monthlyGoalTempId\":\"m1\",\"title\":\"주간\","
			+ "\"measurementType\":\"NUMERIC\",\"targetValue\":10}],"
			+ "\"actions\":[{\"type\":\"GOAL\",\"weeklyGoalTempId\":\"w1\",\"title\":\"행동1\",\"contributionValue\":2},"
			+ "{\"type\":\"MAINTENANCE\",\"title\":\"장보기\"}]}";

	private int rows(String table) {
		return count("SELECT COUNT(*) FROM " + table);
	}

	private BigDecimal rate(String table, long id) {
		return jdbc.queryForObject("SELECT progress_rate FROM " + table + " WHERE id = ?", BigDecimal.class, id);
	}

	private long idAt(MvcResult r, String path) throws Exception {
		return ((Number) JsonPath.read(api.body(r), path)).longValue();
	}

	// ------------------------------------------------------------ 성공

	@Test
	void createsWholeTreeAndReturnsTempIdMap() throws Exception {
		putDraft(1L, 2, "{\"a\":1}");
		MvcResult r = api.post(COMPLETE, 1L, BASIC);
		api.expect(r, 201);
		long y = idAt(r, "$.data.yearlyGoals.y1");
		long m = idAt(r, "$.data.monthlyGoals.m1");
		long w = idAt(r, "$.data.weeklyGoals.w1");
		List<Integer> actions = JsonPath.read(api.body(r), "$.data.actions");
		assertThat(actions).hasSize(2);
		assertThat(count("SELECT COUNT(*) FROM monthly_goals WHERE id = ? AND yearly_goal_id = ?", m, y)).isEqualTo(1);
		assertThat(count("SELECT COUNT(*) FROM weekly_goals WHERE id = ? AND monthly_goal_id = ?", w, m)).isEqualTo(1);
		assertThat(count("SELECT COUNT(*) FROM yearly_goals WHERE id = ? AND user_id = 1 AND target_year = 2026", y))
				.isEqualTo(1);
		// 요청 순서대로 id가 돌아온다: 첫 행동은 GOAL, 둘째는 MAINTENANCE.
		assertThat(count("SELECT COUNT(*) FROM actions WHERE id = ? AND weekly_goal_id = ?", actions.get(0), w))
				.isEqualTo(1);
		assertThat(count("SELECT COUNT(*) FROM actions WHERE id = ? AND weekly_goal_id IS NULL", actions.get(1)))
				.isEqualTo(1);
		// 임시 저장은 같은 트랜잭션에서 지워진다.
		assertThat(rows("onboarding_drafts")).isZero();
		api.expect(api.get(DRAFT, 1L), 200, "$.data", null);
	}

	@Test
	void mixesTempIdsWithExistingIds() throws Exception {
		long y = api.yearly(1L, "기존 연간");
		long m = api.monthly(1L, y, "기존 월간");
		String body = "{\"monthlyGoals\":[{\"tempId\":\"m1\",\"yearlyGoalId\":" + y + ",\"title\":\"새 월간\"}],"
				+ "\"weeklyGoals\":[{\"tempId\":\"w1\",\"monthlyGoalTempId\":\"m1\",\"title\":\"새 주간\"},"
				+ "{\"tempId\":\"w2\",\"monthlyGoalId\":" + m + ",\"title\":\"기존 월간 아래\"}]}";
		MvcResult r = api.post(COMPLETE, 1L, body);
		api.expect(r, 201);
		long newM = idAt(r, "$.data.monthlyGoals.m1");
		assertThat(count("SELECT COUNT(*) FROM monthly_goals WHERE id = ? AND yearly_goal_id = ?", newM, y))
				.isEqualTo(1);
		assertThat(count("SELECT COUNT(*) FROM weekly_goals WHERE id = ? AND monthly_goal_id = ?",
				idAt(r, "$.data.weeklyGoals.w2"), m)).isEqualTo(1);
		assertThat(count("SELECT COUNT(*) FROM weekly_goals WHERE id = ? AND monthly_goal_id = ?",
				idAt(r, "$.data.weeklyGoals.w1"), newM)).isEqualTo(1);
	}

	@Test
	void actionLinkedByMonthlyTempIdPicksWeeklyContainingDate() throws Exception {
		String body = "{\"yearlyGoals\":[{\"tempId\":\"y1\",\"title\":\"Y\"}],"
				+ "\"monthlyGoals\":[{\"tempId\":\"m1\",\"yearlyGoalTempId\":\"y1\",\"title\":\"M\"}],"
				+ "\"weeklyGoals\":[{\"tempId\":\"w1\",\"monthlyGoalTempId\":\"m1\",\"title\":\"W\"}],"
				+ "\"actions\":[{\"type\":\"GOAL\",\"monthlyGoalTempId\":\"m1\",\"title\":\"A\"}]}";
		MvcResult r = api.post(COMPLETE, 1L, body);
		api.expect(r, 201);
		long action = idAt(r, "$.data.actions[0]");
		assertThat(count("SELECT COUNT(*) FROM actions WHERE id = ? AND weekly_goal_id = ?", action,
				idAt(r, "$.data.weeklyGoals.w1"))).isEqualTo(1);
	}

	@Test
	void actionRulesFollowIndividualCreation() throws Exception {
		// 체크형 주간 목표에 기여량은 줄 수 없다(422 GOAL_NOT_NUMERIC), 전체 롤백.
		String checklistWithContribution = "{\"yearlyGoals\":[{\"tempId\":\"y1\",\"title\":\"Y\"}],"
				+ "\"monthlyGoals\":[{\"tempId\":\"m1\",\"yearlyGoalTempId\":\"y1\",\"title\":\"M\"}],"
				+ "\"weeklyGoals\":[{\"tempId\":\"w1\",\"monthlyGoalTempId\":\"m1\",\"title\":\"W\"}],"
				+ "\"actions\":[{\"type\":\"GOAL\",\"weeklyGoalTempId\":\"w1\",\"title\":\"A\",\"contributionValue\":1}]}";
		api.expect(api.post(COMPLETE, 1L, checklistWithContribution), 422, "$.code", "GOAL_NOT_NUMERIC");
		assertThat(rows("yearly_goals")).isZero();
		// 현재 주 이후 날짜의 GOAL 행동은 만들 수 없다(422).
		String future = BASIC.replace("\"title\":\"행동1\"", "\"title\":\"행동1\",\"performedDate\":\"2026-11-20\"");
		assertThat(api.status(api.post(COMPLETE, 1L, future))).isEqualTo(422);
		assertThat(rows("yearly_goals")).isZero();
		// 월간·주간을 함께 보냈는데 부모가 아니면 400, 필드 경로는 actions[i].monthlyGoalTempId.
		String mismatch = "{\"yearlyGoals\":[{\"tempId\":\"y1\",\"title\":\"Y\"}],"
				+ "\"monthlyGoals\":[{\"tempId\":\"m1\",\"yearlyGoalTempId\":\"y1\",\"title\":\"M1\"},"
				+ "{\"tempId\":\"m2\",\"yearlyGoalTempId\":\"y1\",\"title\":\"M2\"}],"
				+ "\"weeklyGoals\":[{\"tempId\":\"w1\",\"monthlyGoalTempId\":\"m1\",\"title\":\"W\"}],"
				+ "\"actions\":[{\"type\":\"GOAL\",\"monthlyGoalTempId\":\"m2\",\"weeklyGoalTempId\":\"w1\","
				+ "\"title\":\"A\"}]}";
		assertFieldError(api.post(COMPLETE, 1L, mismatch), "actions[0].monthlyGoalTempId", "INVALID_FORMAT");
		assertThat(rows("yearly_goals")).isZero();
	}

	@Test
	void progressIsComputedLikeIndividualCreation() throws Exception {
		// 체크형 주간 목표(행동 2개)와 수치형 주간 목표(목표 10, 행동 기여 2)를 한 월간 아래에 만든다.
		String body = "{\"yearlyGoals\":[{\"tempId\":\"y1\",\"title\":\"Y\"}],"
				+ "\"monthlyGoals\":[{\"tempId\":\"m1\",\"yearlyGoalTempId\":\"y1\",\"title\":\"M\"}],"
				+ "\"weeklyGoals\":[{\"tempId\":\"c\",\"monthlyGoalTempId\":\"m1\",\"title\":\"체크\"},"
				+ "{\"tempId\":\"n\",\"monthlyGoalTempId\":\"m1\",\"title\":\"수치\",\"measurementType\":\"NUMERIC\","
				+ "\"targetValue\":10}],"
				+ "\"actions\":[{\"type\":\"GOAL\",\"weeklyGoalTempId\":\"c\",\"title\":\"c1\"},"
				+ "{\"type\":\"GOAL\",\"weeklyGoalTempId\":\"c\",\"title\":\"c2\"},"
				+ "{\"type\":\"GOAL\",\"weeklyGoalTempId\":\"n\",\"title\":\"n1\",\"contributionValue\":2}]}";
		MvcResult r = api.post(COMPLETE, 1L, body);
		api.expect(r, 201);
		long c = idAt(r, "$.data.weeklyGoals.c");
		long n = idAt(r, "$.data.weeklyGoals.n");
		long m = idAt(r, "$.data.monthlyGoals.m1");
		assertThat(rate("weekly_goals", c)).isEqualByComparingTo("0");
		assertThat(rate("monthly_goals", m)).isEqualByComparingTo("0");
		// 체크형은 연결 행동 수가 분모다: 2개 중 1개 완료 = 50%. 수치형은 2/10 = 20%. 월간은 가중치 1:1 평균 35%.
		long c1 = idAt(r, "$.data.actions[0]");
		long n1 = idAt(r, "$.data.actions[2]");
		api.expect(api.complete(1L, c1, true), 200);
		api.expect(api.complete(1L, n1, true), 200);
		assertThat(rate("weekly_goals", c)).isEqualByComparingTo("50");
		assertThat(rate("weekly_goals", n)).isEqualByComparingTo("20");
		assertThat(rate("monthly_goals", m)).isEqualByComparingTo("35");
	}

	// ------------------------------------------------------------ 구조 400

	private void assertStructure(String body, String field, String reason) throws Exception {
		assertFieldError(api.post(COMPLETE, 1L, body), field, reason);
		// 400은 DB를 건드리기 전에 끝난다.
		assertThat(rows("yearly_goals")).isZero();
	}

	@Test
	void emptyRequestIsRejected() throws Exception {
		assertStructure("{}", "yearlyGoals", "REQUIRED");
	}

	@Test
	void missingOrDuplicateTempId() throws Exception {
		assertStructure("{\"yearlyGoals\":[{\"title\":\"a\"}]}", "yearlyGoals[0].tempId", "REQUIRED");
		assertStructure("{\"yearlyGoals\":[{\"tempId\":\" \",\"title\":\"a\"}]}", "yearlyGoals[0].tempId", "REQUIRED");
		assertStructure("{\"yearlyGoals\":[{\"tempId\":\"y\",\"title\":\"a\"},{\"tempId\":\"y\",\"title\":\"b\"}]}",
				"yearlyGoals[1].tempId", "INVALID_FORMAT");
	}

	@Test
	void fieldRulesUseIndexedPaths() throws Exception {
		assertStructure("{\"yearlyGoals\":[{\"tempId\":\"y\",\"title\":\"\"}]}", "yearlyGoals[0].title", "REQUIRED");
		assertStructure("{\"yearlyGoals\":[{\"tempId\":\"y\",\"title\":\"a\"},{\"tempId\":\"z\",\"title\":\"b\","
				+ "\"color\":\"red\"}]}", "yearlyGoals[1].color", "INVALID_FORMAT");
		assertStructure("{\"yearlyGoals\":[{\"tempId\":\"y\",\"title\":\"a\"}],\"monthlyGoals\":[{\"tempId\":\"m\","
				+ "\"yearlyGoalTempId\":\"y\",\"title\":\"" + "가".repeat(101) + "\"}]}", "monthlyGoals[0].title",
				"TOO_LONG");
		assertStructure("{\"yearlyGoals\":[{\"tempId\":\"y\",\"title\":\"a\"}],\"monthlyGoals\":[{\"tempId\":\"m\","
				+ "\"yearlyGoalTempId\":\"y\",\"title\":\"a\"}],\"weeklyGoals\":[{\"tempId\":\"w\","
				+ "\"monthlyGoalTempId\":\"m\",\"title\":\"a\",\"measurementType\":\"NUMERIC\"}]}",
				"weeklyGoals[0].targetValue", "REQUIRED");
		assertStructure("{\"actions\":[{\"type\":\"MAINTENANCE\",\"title\":\"\"}]}", "actions[0].title", "REQUIRED");
		assertStructure("{\"actions\":[{\"type\":\"MAINTENANCE\",\"title\":\"a\",\"weeklyGoalTempId\":null}]}",
				"actions[0].weeklyGoalTempId", "INVALID_FORMAT");
		assertStructure("{\"actions\":[{\"type\":\"GOAL\",\"title\":\"a\"}]}", "actions[0].weeklyGoalId", "REQUIRED");
	}

	@Test
	void parentMustBeExactlyOneOfTempIdOrId() throws Exception {
		String y = "\"yearlyGoals\":[{\"tempId\":\"y\",\"title\":\"a\"}],";
		assertStructure("{" + y + "\"monthlyGoals\":[{\"tempId\":\"m\",\"title\":\"a\"}]}",
				"monthlyGoals[0].yearlyGoalId", "REQUIRED");
		assertStructure("{" + y + "\"monthlyGoals\":[{\"tempId\":\"m\",\"yearlyGoalTempId\":\"y\","
				+ "\"yearlyGoalId\":5,\"title\":\"a\"}]}", "monthlyGoals[0].yearlyGoalTempId", "INVALID_FORMAT");
		assertStructure("{\"monthlyGoals\":[{\"tempId\":\"m\",\"yearlyGoalId\":1,\"title\":\"a\"}],"
				+ "\"weeklyGoals\":[{\"tempId\":\"w\",\"title\":\"a\"}]}", "weeklyGoals[0].monthlyGoalId", "REQUIRED");
		assertStructure("{\"monthlyGoals\":[{\"tempId\":\"m\",\"yearlyGoalId\":1,\"title\":\"a\"}],"
				+ "\"weeklyGoals\":[{\"tempId\":\"w\",\"monthlyGoalTempId\":\"m\",\"monthlyGoalId\":2,"
				+ "\"title\":\"a\"}]}", "weeklyGoals[0].monthlyGoalTempId", "INVALID_FORMAT");
	}

	@Test
	void unknownTempIdReferenceIs400() throws Exception {
		assertStructure("{\"yearlyGoals\":[{\"tempId\":\"y\",\"title\":\"a\"}],\"monthlyGoals\":[{\"tempId\":\"m\","
				+ "\"yearlyGoalTempId\":\"nope\",\"title\":\"a\"}]}", "monthlyGoals[0].yearlyGoalTempId",
				"INVALID_FORMAT");
		assertStructure("{\"monthlyGoals\":[{\"tempId\":\"m\",\"yearlyGoalId\":1,\"title\":\"a\"}],"
				+ "\"weeklyGoals\":[{\"tempId\":\"w\",\"monthlyGoalTempId\":\"nope\",\"title\":\"a\"}]}",
				"weeklyGoals[0].monthlyGoalTempId", "INVALID_FORMAT");
		assertStructure("{\"actions\":[{\"type\":\"GOAL\",\"weeklyGoalTempId\":\"nope\",\"title\":\"a\"}]}",
				"actions[0].weeklyGoalTempId", "INVALID_FORMAT");
		assertStructure("{\"actions\":[{\"type\":\"GOAL\",\"monthlyGoalTempId\":\"nope\",\"title\":\"a\"}]}",
				"actions[0].monthlyGoalTempId", "INVALID_FORMAT");
	}

	@Test
	void collectsErrorsFromSeveralItemsAtOnce() throws Exception {
		MvcResult r = api.post(COMPLETE, 1L, "{\"yearlyGoals\":[{\"tempId\":\"a\",\"title\":\"\"},"
				+ "{\"tempId\":\"b\",\"title\":\"\"}]}");
		List<Map<String, String>> errors = JsonPath.read(api.body(r), "$.details.fieldErrors");
		assertThat(errors).extracting(e -> e.get("field")).contains("yearlyGoals[0].title", "yearlyGoals[1].title");
	}

	// ------------------------------------------------------------ 한도·부모 오류

	@Test
	void yearlyLimitCountsExistingAndNewTogether() throws Exception {
		api.yearly(1L, "기존1");
		api.yearly(1L, "기존2");
		String body = "{\"yearlyGoals\":[{\"tempId\":\"a\",\"title\":\"새1\"},{\"tempId\":\"b\",\"title\":\"새2\"}]}";
		api.expect(api.post(COMPLETE, 1L, body), 409, "$.code", "GOAL_LIMIT_EXCEEDED");
		assertThat(rows("yearly_goals")).isEqualTo(2);
		// 새 연간 1개는 들어간다(2 + 1 = 3).
		api.expect(api.post(COMPLETE, 1L, "{\"yearlyGoals\":[{\"tempId\":\"a\",\"title\":\"새1\"}]}"), 201);
		// 다른 사용자의 한도에는 영향이 없다.
		api.expect(api.post(COMPLETE, 2L, "{\"yearlyGoals\":[{\"tempId\":\"a\",\"title\":\"x\"},"
				+ "{\"tempId\":\"b\",\"title\":\"y\"},{\"tempId\":\"c\",\"title\":\"z\"}]}"), 201);
	}

	@Test
	void foreignOrDeletedParentIs422Unavailable() throws Exception {
		long other = api.yearly(2L, "타인 연간");
		String body = "{\"monthlyGoals\":[{\"tempId\":\"m\",\"yearlyGoalId\":" + other + ",\"title\":\"a\"}]}";
		api.expect(api.post(COMPLETE, 1L, body), 422, "$.code", "GOAL_PARENT_UNAVAILABLE");
		api.expect(api.post(COMPLETE, 1L, "{\"weeklyGoals\":[{\"tempId\":\"w\",\"monthlyGoalId\":999999,"
				+ "\"title\":\"a\"}]}"), 422, "$.code", "GOAL_PARENT_UNAVAILABLE");
		// 삭제된 부모도 같은 응답이다.
		long mine = api.yearly(1L, "내 연간");
		jdbc.update("UPDATE yearly_goals SET deleted_at = CURRENT_TIMESTAMP WHERE id = ?", mine);
		api.expect(api.post(COMPLETE, 1L, "{\"monthlyGoals\":[{\"tempId\":\"m\",\"yearlyGoalId\":" + mine
				+ ",\"title\":\"a\"}]}"), 422, "$.code", "GOAL_PARENT_UNAVAILABLE");
		assertThat(rows("monthly_goals")).isZero();
	}

	@Test
	void parentOutsideThePeriodIs422PeriodMismatch() throws Exception {
		// 월간 목표는 올해 연간 아래에만 만들 수 있다. 내년 연간은 기간이 맞지 않는다.
		long next = api.yearly(1L, "내년 연간", ",\"targetYear\":2027");
		api.expect(api.post(COMPLETE, 1L, "{\"monthlyGoals\":[{\"tempId\":\"m\",\"yearlyGoalId\":" + next
				+ ",\"title\":\"a\"}]}"), 422, "$.code", "GOAL_PARENT_PERIOD_MISMATCH");
		// 주간 목표는 이번 달 월간 아래에만. 지난달 월간(JDBC 시드)은 기간이 맞지 않는다.
		long y = api.yearly(1L, "올해");
		long lastMonth = GoalTestSupport.seedMonthly(jdbc, y, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31),
				"지난달");
		api.expect(api.post(COMPLETE, 1L, "{\"weeklyGoals\":[{\"tempId\":\"w\",\"monthlyGoalId\":" + lastMonth
				+ ",\"title\":\"a\"}]}"), 422, "$.code", "GOAL_PARENT_PERIOD_MISMATCH");
	}

	// ------------------------------------------------------------ 롤백 (Review Focus 3)

	@Test
	void failureOnLastActionRollsBackEverythingAndKeepsDraft() throws Exception {
		long foreignY = api.yearly(2L, "타인 연간");
		long foreignM = api.monthly(2L, foreignY, "타인 월간");
		long foreignW = api.weekly(2L, foreignM, "타인 주간");
		putDraft(1L, 3, "{\"keep\":true}");
		String body = BASIC.replace("{\"type\":\"MAINTENANCE\",\"title\":\"장보기\"}",
				"{\"type\":\"GOAL\",\"weeklyGoalId\":" + foreignW + ",\"title\":\"남의 것\"}");
		api.expect(api.post(COMPLETE, 1L, body), 422, "$.code", "GOAL_PARENT_UNAVAILABLE");
		assertThat(count("SELECT COUNT(*) FROM yearly_goals WHERE user_id = 1")).isZero();
		assertThat(rows("monthly_goals")).isEqualTo(1); // 타인 것만
		assertThat(rows("weekly_goals")).isEqualTo(1);
		assertThat(rows("actions")).isZero();
		api.expect(api.get(DRAFT, 1L), 200, "$.data.draftData.keep", true, "$.data.version", v0());
	}

	@Test
	void failureAfterChangingExistingProgressRestoresRatesLogsAndDraft() throws Exception {
		long foreignY = api.yearly(2L, "타인 연간");
		long foreignM = api.monthly(2L, foreignY, "타인 월간");
		long foreignW = api.weekly(2L, foreignM, "타인 주간");
		// 내 기존 체크형 주간 목표: 행동 2개 중 1개 완료 = 50%, 월간도 50%.
		long y = api.yearly(1L, "내 연간");
		long m = api.monthly(1L, y, "내 월간");
		long w = api.weekly(1L, m, "내 주간");
		long done = api.goalAction(1L, w, "완료할 것", "");
		api.goalAction(1L, w, "남은 것", "");
		api.expect(api.complete(1L, done, true), 200);
		BigDecimal weeklyBefore = rate("weekly_goals", w);
		BigDecimal monthlyBefore = rate("monthly_goals", m);
		assertThat(weeklyBefore).isEqualByComparingTo("50");
		int logsBefore = rows("goal_progress_logs");
		int actionsBefore = rows("actions");
		putDraft(1L, 3, "{\"keep\":true}");

		// 첫 행동은 기존 주간에 행동을 추가해 진행률(50 -> 33.33)을 바꾸고 진행 기록을 남긴다. 마지막 행동에서 실패한다.
		String body = "{\"actions\":[{\"type\":\"GOAL\",\"weeklyGoalId\":" + w + ",\"title\":\"추가\"},"
				+ "{\"type\":\"GOAL\",\"weeklyGoalId\":" + foreignW + ",\"title\":\"남의 것\"}]}";
		api.expect(api.post(COMPLETE, 1L, body), 422, "$.code", "GOAL_PARENT_UNAVAILABLE");

		assertThat(rate("weekly_goals", w)).isEqualByComparingTo(weeklyBefore);
		assertThat(rate("monthly_goals", m)).isEqualByComparingTo(monthlyBefore);
		assertThat(rows("goal_progress_logs")).isEqualTo(logsBefore);
		assertThat(rows("actions")).isEqualTo(actionsBefore);
		api.expect(api.get(DRAFT, 1L), 200, "$.data.draftData.keep", true, "$.data.version", v0());
	}

	@Test
	void draftRecreatedAfterCompleteGetsNewVersionSoStaleTabCannotOverwrite() throws Exception {
		MvcResult first = api.put(DRAFT, 1L, "{\"currentStep\":1,\"draftData\":{\"a\":1}}");
		long oldVersion = ((Number) JsonPath.read(api.body(first), "$.data.version")).longValue();
		api.expect(api.post(COMPLETE, 1L, BASIC), 201);
		MvcResult again = api.put(DRAFT, 1L, "{\"currentStep\":2,\"draftData\":{\"a\":2}}");
		long newVersion = ((Number) JsonPath.read(api.body(again), "$.data.version")).longValue();
		assertThat(newVersion).isNotEqualTo(oldVersion);
		api.expect(api.put(DRAFT, 1L, "{\"currentStep\":3,\"draftData\":{\"a\":3},\"version\":" + oldVersion + "}"),
				409, "$.code", "VERSION_CONFLICT", "$.details.currentVersion", newVersion);
		api.expect(api.get(DRAFT, 1L), 200, "$.data.draftData.a", 2);
	}

	@Test
	void requiresLogin() throws Exception {
		MvcResult r = mockMvc.perform(MockMvcRequestBuilders.post(COMPLETE)).andReturn();
		assertThat(r.getResponse().getStatus()).isIn(401, 403);
	}
}
