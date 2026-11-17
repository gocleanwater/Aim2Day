package com.aim2day.goal.actions;

import static org.assertj.core.api.Assertions.assertThat;

import com.aim2day.goal.support.GoalTestSupport;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

class ActionCreateApiTests extends ActionTestBase {

	@Test
	void createsWithWeeklyGoalId() throws Exception {
		MvcResult result = api.post("/api/actions", 1L,
				"{\"type\":\"GOAL\",\"weeklyGoalId\":" + w1 + ",\"title\":\"  스택 정리  \",\"dueDate\":\"2026-11-14\"}");
		api.expect(result, 201,
				"$.data.type", "GOAL",
				"$.data.title", "스택 정리",
				"$.data.performedDate", "2026-11-12",
				"$.data.dueDate", "2026-11-14",
				"$.data.daysUntilDue", 2,
				"$.data.isCompleted", false,
				"$.data.completedAt", null,
				"$.data.contributionValue", null,
				"$.data.goalPath.yearlyGoal.id", (int) yearly,
				"$.data.goalPath.monthlyGoal.id", (int) monthly,
				"$.data.goalPath.monthlyGoal.month", "2026-11",
				"$.data.goalPath.weeklyGoal.id", (int) w1,
				"$.data.goalPath.weeklyGoal.title", "체크 주간",
				"$.data.pathStatus", null,
				"$.data.displayColor", "#3B82F6",
				"$.data.placement.state", "UNCLASSIFIED",
				"$.data.placement.displayed", null,
				"$.data.version", 0);
	}

	@Test
	void checklistCreateRecalculatesToRecordedZero() throws Exception {
		api.expect(api.get("/api/goals/weekly/" + w1, 1L), 200, "$.data.progress.state", "NO_RECORD");
		api.goalAction(1L, w1, "첫 행동", "");
		// 0/1 -> 기록 있음 0.00. 저장값이 그대로 0이라 진행 기록은 남지 않는다.
		api.expect(api.get("/api/goals/weekly/" + w1, 1L), 200,
				"$.data.progress.state", "RECORDED", "$.data.progress.rate", 0.0,
				"$.data.actionCounts.total", 1, "$.data.actionCounts.completed", 0);
	}

	@Test
	void creatingAfterCompletionLowersChecklistRate() throws Exception {
		long a = api.goalAction(1L, w1, "첫 행동", "");
		api.expect(api.complete(1L, a, true), 200);
		assertThat(weeklyRate(w1)).isEqualByComparingTo("100.00");
		// 분모가 2가 되어 50%로 내려간다. 월간(w1 50 + n1 0)도 따라 바뀐다.
		api.goalAction(1L, w1, "둘째 행동", "");
		assertThat(weeklyRate(w1)).isEqualByComparingTo("50.00");
		assertThat(monthlyRate(monthly)).isEqualByComparingTo("25.00");
		assertThat(count("SELECT COUNT(*) FROM goal_progress_logs WHERE target_type = 'WEEKLY_GOAL' AND memo = ?",
				"행동 추가: 둘째 행동")).isEqualTo(1);
	}

	@Test
	void monthlyOnlyLinksTheSingleCandidate() throws Exception {
		jdbc.update("UPDATE weekly_goals SET deleted_at = CURRENT_TIMESTAMP WHERE id = ?", n1);
		MvcResult result = api.post("/api/actions", 1L,
				"{\"type\":\"GOAL\",\"monthlyGoalId\":" + monthly + ",\"title\":\"자동 연결\"}");
		api.expect(result, 201, "$.data.goalPath.weeklyGoal.id", (int) w1);
	}

	@Test
	void monthlyOnlyWithNoCandidateIs422Required() throws Exception {
		// 지난 주(11-01~11-07)에 만들어 둔 주간 목표가 없다. 없는 주간 목표를 새로 만들어 주지 않는다.
		MvcResult result = api.post("/api/actions", 1L,
				"{\"type\":\"GOAL\",\"monthlyGoalId\":" + monthly + ",\"title\":\"지난 주\",\"performedDate\":\"2026-11-03\"}");
		api.expect(result, 422,
				"$.code", "ACTION_WEEKLY_GOAL_REQUIRED",
				"$.details.monthlyGoalId", (int) monthly,
				"$.details.performedDate", "2026-11-03");
	}

	@Test
	void monthlyOnlyLinksPastWeekWhenItExists() throws Exception {
		long past = GoalTestSupport.seedWeekly(jdbc, monthly, LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 7),
				"지난 주 목표", "CHECKLIST", null);
		api.expect(api.post("/api/actions", 1L,
				"{\"type\":\"GOAL\",\"monthlyGoalId\":" + monthly + ",\"title\":\"뒤늦은 기록\",\"performedDate\":\"2026-11-03\"}"),
				201, "$.data.goalPath.weeklyGoal.id", (int) past, "$.data.performedDate", "2026-11-03");
	}

	@Test
	void monthlyOnlyWithSeveralCandidatesIs422Ambiguous() throws Exception {
		MvcResult result = api.post("/api/actions", 1L,
				"{\"type\":\"GOAL\",\"monthlyGoalId\":" + monthly + ",\"title\":\"모호\"}");
		api.expect(result, 422,
				"$.code", "ACTION_WEEKLY_GOAL_AMBIGUOUS",
				"$.details.candidates.length()", 2,
				"$.details.candidates[0].id", (int) w1,
				"$.details.candidates[0].title", "체크 주간",
				"$.details.candidates[0].startDate", "2026-11-08",
				"$.details.candidates[0].endDate", "2026-11-14",
				"$.details.candidates[1].id", (int) n1);
	}

	@Test
	void goalWithoutAnyParentIs400() throws Exception {
		api.expect(api.post("/api/actions", 1L, "{\"type\":\"GOAL\",\"title\":\"목표 없음\"}"), 400,
				"$.code", "VALIDATION_FAILED", "$.details.fieldErrors[0].reason", "REQUIRED");
	}

	@Test
	void monthlyGoalNotParentOfWeeklyIs400() throws Exception {
		long otherMonthly = GoalTestSupport.seedMonthly(jdbc, yearly, LocalDate.of(2026, 11, 1),
				LocalDate.of(2026, 11, 30), "다른 월간");
		api.expect(api.post("/api/actions", 1L, "{\"type\":\"GOAL\",\"monthlyGoalId\":" + otherMonthly
				+ ",\"weeklyGoalId\":" + w1 + ",\"title\":\"모순\"}"), 400,
				"$.code", "VALIDATION_FAILED", "$.details.fieldErrors[0].field", "monthlyGoalId");
	}

	@Test
	void maintenanceRejectsGoalFieldsAndAllowsFutureDates() throws Exception {
		api.expect(api.post("/api/actions", 1L,
				"{\"type\":\"MAINTENANCE\",\"title\":\"청소\",\"weeklyGoalId\":" + w1 + "}"), 400,
				"$.code", "VALIDATION_FAILED");
		api.expect(api.post("/api/actions", 1L,
				"{\"type\":\"MAINTENANCE\",\"title\":\"청소\",\"monthlyGoalId\":" + monthly + "}"), 400);
		api.expect(api.post("/api/actions", 1L,
				"{\"type\":\"MAINTENANCE\",\"title\":\"청소\",\"contributionValue\":1}"), 400);
		// 명시적 null도 "보낸 것"이라 400이다. 생략하면 201이다.
		for (String field : new String[] {"monthlyGoalId", "weeklyGoalId", "contributionValue"}) {
			api.expect(api.post("/api/actions", 1L,
					"{\"type\":\"MAINTENANCE\",\"title\":\"청소\",\"" + field + "\":null}"), 400,
					"$.code", "VALIDATION_FAILED", "$.details.fieldErrors[0].field", field);
		}
		api.expect(api.post("/api/actions", 1L, "{\"type\":\"MAINTENANCE\",\"title\":\"생략\"}"), 201);
		// 날짜 제한이 없다.
		api.expect(api.post("/api/actions", 1L,
				"{\"type\":\"MAINTENANCE\",\"title\":\"연말 대청소\",\"performedDate\":\"2026-12-25\"}"), 201,
				"$.data.type", "MAINTENANCE",
				"$.data.goalPath", null,
				"$.data.pathStatus", null,
				"$.data.contributionValue", null,
				"$.data.displayColor", "#9CA3AF",
				"$.data.performedDate", "2026-12-25");
	}

	@Test
	void goalBeyondCurrentWeekIs422OutOfRange() throws Exception {
		api.expect(api.post("/api/actions", 1L, "{\"type\":\"GOAL\",\"weeklyGoalId\":" + w1
				+ ",\"title\":\"다음 주\",\"performedDate\":\"2026-11-15\"}"), 422,
				"$.code", "ACTION_DATE_OUT_OF_RANGE",
				"$.details.currentWeekEndDate", "2026-11-14",
				"$.details.performedDate", "2026-11-15");
		// monthlyGoalId만 보내도 연결 판단에 앞서 같은 오류다.
		api.expect(api.post("/api/actions", 1L, "{\"type\":\"GOAL\",\"monthlyGoalId\":" + monthly
				+ ",\"title\":\"다음 주\",\"performedDate\":\"2026-11-20\"}"), 422, "$.code", "ACTION_DATE_OUT_OF_RANGE");
		// 현재 주의 마지막 날은 된다.
		api.expect(api.post("/api/actions", 1L, "{\"type\":\"GOAL\",\"weeklyGoalId\":" + w1
				+ ",\"title\":\"주말\",\"performedDate\":\"2026-11-14\"}"), 201);
	}

	@Test
	void contributionValueOnChecklistIs422AndOnNumericIsKept() throws Exception {
		api.expect(api.post("/api/actions", 1L, "{\"type\":\"GOAL\",\"weeklyGoalId\":" + w1
				+ ",\"title\":\"체크형\",\"contributionValue\":2}"), 422, "$.code", "GOAL_NOT_NUMERIC");
		api.expect(api.post("/api/actions", 1L, "{\"type\":\"GOAL\",\"weeklyGoalId\":" + n1
				+ ",\"title\":\"수치형\",\"contributionValue\":2}"), 201, "$.data.contributionValue", 2.0);
		// 수치형 주간 목표의 진행률은 행동 생성으로 바뀌지 않는다(완료 때 가산된다).
		assertThat(currentValue(n1)).isEqualByComparingTo("0.00");
	}

	@Test
	void hiddenOrForeignParentIs422Unavailable() throws Exception {
		long other = api.yearly(2L, "남의 연간");
		long otherMonthly = api.monthly(2L, other, "남의 월간");
		long otherWeekly = api.weekly(2L, otherMonthly, "남의 주간");
		api.expect(api.post("/api/actions", 1L, "{\"type\":\"GOAL\",\"weeklyGoalId\":" + otherWeekly
				+ ",\"title\":\"남의 것\"}"), 422,
				"$.code", "GOAL_PARENT_UNAVAILABLE", "$.details.parentLevel", "WEEKLY",
				"$.details.parentId", (int) otherWeekly);
		api.expect(api.post("/api/actions", 1L, "{\"type\":\"GOAL\",\"monthlyGoalId\":" + otherMonthly
				+ ",\"title\":\"남의 것\"}"), 422, "$.details.parentLevel", "MONTHLY");

		jdbc.update("UPDATE yearly_goals SET deleted_at = CURRENT_TIMESTAMP WHERE id = ?", yearly);
		api.expect(api.post("/api/actions", 1L, "{\"type\":\"GOAL\",\"weeklyGoalId\":" + w1
				+ ",\"title\":\"숨은 부모\"}"), 422, "$.code", "GOAL_PARENT_UNAVAILABLE");
		api.expect(api.post("/api/actions", 1L, "{\"type\":\"GOAL\",\"monthlyGoalId\":" + monthly
				+ ",\"title\":\"숨은 부모\"}"), 422, "$.code", "GOAL_PARENT_UNAVAILABLE");
	}

	@Test
	void validatesInput() throws Exception {
		api.expect(api.post("/api/actions", 1L, "{\"title\":\"유형 없음\"}"), 400,
				"$.details.fieldErrors[0].field", "type", "$.details.fieldErrors[0].reason", "REQUIRED");
		api.expect(api.post("/api/actions", 1L, "{\"type\":\"MAINTENANCE\",\"title\":\"   \"}"), 400,
				"$.details.fieldErrors[0].field", "title", "$.details.fieldErrors[0].reason", "REQUIRED");
		api.expect(api.post("/api/actions", 1L, "{\"type\":\"MAINTENANCE\",\"title\":\"" + "가".repeat(256) + "\"}"),
				400, "$.details.fieldErrors[0].reason", "TOO_LONG");
		api.expect(api.post("/api/actions", 1L, "{\"type\":\"MAINTENANCE\",\"title\":\"" + "가".repeat(255) + "\"}"),
				201);
		api.expect(api.post("/api/actions", 1L, "{\"type\":\"GOAL\",\"weeklyGoalId\":" + n1
				+ ",\"title\":\"기여량\",\"contributionValue\":0}"), 400,
				"$.details.fieldErrors[0].field", "contributionValue",
				"$.details.fieldErrors[0].reason", "OUT_OF_RANGE");
		api.expect(api.post("/api/actions", 1L, "{\"type\":\"GOAL\",\"weeklyGoalId\":" + n1
				+ ",\"title\":\"기여량\",\"contributionValue\":1.005}"), 400,
				"$.details.fieldErrors[0].reason", "INVALID_FORMAT");
		api.expect(api.post("/api/actions", 1L, "{\"type\":\"OTHER\",\"title\":\"모르는 유형\"}"), 400,
				"$.code", "MALFORMED_REQUEST");
	}

	@Test
	void weeklyDetailListsActionsAndCounts() throws Exception {
		long a = api.goalAction(1L, w1, "첫째", "");
		api.goalAction(1L, w1, "둘째", ",\"performedDate\":\"2026-11-10\"");
		GoalTestSupport.seedAction(jdbc, 1L, w1, "삭제됨", LocalDate.of(2026, 11, 9), false, true);
		api.expect(api.complete(1L, a, true), 200);
		api.expect(api.get("/api/goals/weekly/" + w1, 1L), 200,
				"$.data.actionCounts.total", 2,
				"$.data.actionCounts.completed", 1,
				"$.data.progress.rate", 50.0,
				"$.data.actions.length()", 2,
				// 수행일 오름차순: 11-10이 11-12보다 먼저다. 삭제된 행동은 빠진다.
				"$.data.actions[0].title", "둘째",
				"$.data.actions[1].title", "첫째",
				"$.data.actions[1].isCompleted", true,
				"$.data.actions[1].goalPath.weeklyGoal.id", (int) w1);
	}
}
