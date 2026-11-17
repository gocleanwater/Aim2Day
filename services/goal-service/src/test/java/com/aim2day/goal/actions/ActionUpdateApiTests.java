package com.aim2day.goal.actions;

import static org.assertj.core.api.Assertions.assertThat;

import com.aim2day.goal.support.GoalTestSupport;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

class ActionUpdateApiTests extends ActionTestBase {

	private String path(long id) {
		return "/api/actions/" + id;
	}

	private long version(long id) {
		return jdbc.queryForObject("SELECT version FROM actions WHERE id = ?", Long.class, id);
	}

	private MvcResult patch(long id, String fields) throws Exception {
		return api.patch(path(id), 1L, "{" + fields + ",\"version\":" + version(id) + "}");
	}

	/** 수치형 n1(목표 10)에 기여량 2인 행동을 만들어 완료한다. */
	private long completedNumeric(String amount) throws Exception {
		long a = api.goalAction(1L, n1, "문제 풀기", ",\"contributionValue\":" + amount);
		api.expect(api.complete(1L, a, true), 200);
		return a;
	}

	@Test
	void changesPlainFields() throws Exception {
		long a = api.goalAction(1L, w1, "스택 정리", "");
		api.expect(patch(a, "\"title\":\"  스택 정리 끝  \",\"dueDate\":\"2026-11-14\",\"resultSummary\":\"노트 작성\""), 200,
				"$.data.title", "스택 정리 끝", "$.data.dueDate", "2026-11-14", "$.data.daysUntilDue", 2,
				"$.data.resultSummary", "노트 작성", "$.data.version", 1);
		// JSON null은 기한·결과물을 지운다.
		api.expect(patch(a, "\"dueDate\":null,\"resultSummary\":null"), 200,
				"$.data.dueDate", null, "$.data.daysUntilDue", null, "$.data.resultSummary", null, "$.data.version", 2);
		// 필드를 보내지 않으면 유지한다.
		api.expect(patch(a, "\"title\":\"바뀐 제목\""), 200, "$.data.title", "바뀐 제목");
	}

	@Test
	void validatesFields() throws Exception {
		long a = api.goalAction(1L, w1, "스택 정리", "");
		api.expect(patch(a, "\"title\":null"), 400, "$.details.fieldErrors[0].field", "title",
				"$.details.fieldErrors[0].reason", "REQUIRED");
		api.expect(patch(a, "\"title\":\"  \""), 400, "$.details.fieldErrors[0].reason", "REQUIRED");
		api.expect(patch(a, "\"performedDate\":null"), 400, "$.details.fieldErrors[0].field", "performedDate");
		api.expect(patch(a, "\"resultSummary\":\"" + "가".repeat(2001) + "\""), 400,
				"$.details.fieldErrors[0].field", "resultSummary", "$.details.fieldErrors[0].reason", "TOO_LONG");
		api.expect(patch(a, "\"weeklyGoalId\":null"), 400, "$.details.fieldErrors[0].field", "weeklyGoalId");
		api.expect(api.patch(path(a), 1L, "{\"title\":\"버전 없음\"}"), 400,
				"$.details.fieldErrors[0].field", "version", "$.details.fieldErrors[0].reason", "REQUIRED");
		api.expect(patch(a, "\"contributionValue\":1.005"), 400);
	}

	@Test
	void contributionChangeOnCompletedActionAdjustsRecordAndValue() throws Exception {
		long a = completedNumeric("2");
		assertThat(currentValue(n1)).isEqualByComparingTo("2.00");

		// 실제값 2, 기여량 2 -> 5로 수정하면 실제값 5(ERD 8번 규칙).
		api.expect(patch(a, "\"contributionValue\":5"), 200, "$.data.contributionValue", 5.0);
		assertThat(currentValue(n1)).isEqualByComparingTo("5.00");
		assertThat(weeklyRate(n1)).isEqualByComparingTo("50.00");
		assertThat(records(n1)).isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT amount FROM weekly_goal_numeric_records WHERE action_id = ?",
				BigDecimal.class, a)).isEqualByComparingTo("5.00");
		assertThat(monthlyRate(monthly)).isEqualByComparingTo("25.00");

		// null로 바꾸면 기록을 지우고 이전 값을 뺀다.
		api.expect(patch(a, "\"contributionValue\":null"), 200, "$.data.contributionValue", null);
		assertThat(currentValue(n1)).isEqualByComparingTo("0.00");
		assertThat(records(n1)).isZero();
		assertThat(weeklyRate(n1)).isEqualByComparingTo("0.00");

		// null에서 값으로 바꾸면 기록을 만들고 더한다.
		api.expect(patch(a, "\"contributionValue\":3"), 200, "$.data.contributionValue", 3.0);
		assertThat(currentValue(n1)).isEqualByComparingTo("3.00");
		assertThat(records(n1)).isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT record_source FROM weekly_goal_numeric_records WHERE action_id = ?",
				String.class, a)).isEqualTo("ACTION_COMPLETION");
		// 완료를 취소하면 바뀐 기록(3)이 빠진다.
		api.expect(api.complete(1L, a, false), 200, "$.data.weeklyGoal.currentValue", 0.0);
		assertThat(records(n1)).isZero();
	}

	@Test
	void contributionChangeOnIncompleteActionOnlyStoresTheValue() throws Exception {
		long a = api.goalAction(1L, n1, "문제 풀기", ",\"contributionValue\":2");
		api.expect(patch(a, "\"contributionValue\":4"), 200, "$.data.contributionValue", 4.0);
		assertThat(currentValue(n1)).isEqualByComparingTo("0.00");
		assertThat(records(n1)).isZero();
		api.expect(api.complete(1L, a, true), 200, "$.data.weeklyGoal.currentValue", 4.0);
	}

	@Test
	void contributionOnChecklistIs422() throws Exception {
		long a = api.goalAction(1L, w1, "체크형", "");
		api.expect(patch(a, "\"contributionValue\":2"), 422, "$.code", "GOAL_NOT_NUMERIC");
		// 명시적 null은 그대로 둔다.
		api.expect(patch(a, "\"contributionValue\":null"), 200);
	}

	@Test
	void contributionOverflowIs400() throws Exception {
		long big = api.numericWeekly(1L, monthly, "큰 목표", "99999999.99");
		long a = api.goalAction(1L, big, "행동", ",\"contributionValue\":1");
		api.expect(api.complete(1L, a, true), 200);
		jdbc.update("UPDATE weekly_goals SET current_value = 99999999.00 WHERE id = ?", big);
		api.expect(patch(a, "\"contributionValue\":2"), 400, "$.details.fieldErrors[0].reason", "OUT_OF_RANGE");
		// 이전 기여량이 빠진 값 기준이라 99999999.00 - 1 + 1.99 = 99999999.99는 상한 이하다.
		api.expect(patch(a, "\"contributionValue\":1.99"), 200);
		assertThat(currentValue(big)).isEqualByComparingTo("99999999.99");
	}

	@Test
	void moveCompletedNumericActionToAnotherNumericWeekly() throws Exception {
		long a = completedNumeric("2");
		// 월간 목표가 다른 수치형 주간 목표로 옮긴다(Review Focus 4: 두 주간·두 월간 모두 맞아야 한다).
		long otherMonthly = GoalTestSupport.seedMonthly(jdbc, yearly, LocalDate.of(2026, 11, 1),
				LocalDate.of(2026, 11, 30), "다른 월간");
		long n2 = GoalTestSupport.seedWeekly(jdbc, otherMonthly, LocalDate.of(2026, 11, 8), LocalDate.of(2026, 11, 14),
				"다른 수치", "NUMERIC", new BigDecimal("4.00"));
		assertThat(monthlyRate(monthly)).isEqualByComparingTo("10.00");

		api.expect(patch(a, "\"weeklyGoalId\":" + n2), 200,
				"$.data.goalPath.weeklyGoal.id", (int) n2, "$.data.goalPath.monthlyGoal.id", (int) otherMonthly);
		assertThat(currentValue(n1)).isEqualByComparingTo("0.00");
		assertThat(currentValue(n2)).isEqualByComparingTo("2.00");
		assertThat(weeklyRate(n1)).isEqualByComparingTo("0.00");
		assertThat(weeklyRate(n2)).isEqualByComparingTo("50.00");
		assertThat(monthlyRate(monthly)).isEqualByComparingTo("0.00");
		assertThat(monthlyRate(otherMonthly)).isEqualByComparingTo("50.00");
		// 기록은 같은 행으로 새 주간 목표에 옮겨 간다.
		assertThat(records(n1)).isZero();
		assertThat(records(n2)).isEqualTo(1);
		assertThat(count("SELECT COUNT(*) FROM weekly_goal_numeric_records WHERE action_id = ?", a)).isEqualTo(1);
	}

	@Test
	void moveBetweenWeekliesUnderSameMonthlyLeavesNoFakeMonthlyLogs() throws Exception {
		long m2 = api.monthly(1L, yearly, "두 주간 월간");
		long a = api.numericWeekly(1L, m2, "주간 A", "10");
		long b = api.numericWeekly(1L, m2, "주간 B", "10");
		long action = api.goalAction(1L, a, "문제 풀기", ",\"contributionValue\":2");
		api.expect(api.complete(1L, action, true), 200);
		assertThat(monthlyRate(m2)).isEqualByComparingTo("10.00");
		String monthlyLogs = "SELECT COUNT(*) FROM goal_progress_logs WHERE target_type = 'MONTHLY_GOAL' AND target_id = ?";
		String weeklyLogs = "SELECT COUNT(*) FROM goal_progress_logs WHERE target_type = 'WEEKLY_GOAL' AND target_id = ?";
		int monthlyBefore = count(monthlyLogs, m2);
		int aBefore = count(weeklyLogs, a);
		int bBefore = count(weeklyLogs, b);

		api.expect(patch(action, "\"weeklyGoalId\":" + b), 200);

		assertThat(weeklyRate(a)).isEqualByComparingTo("0.00");
		assertThat(weeklyRate(b)).isEqualByComparingTo("20.00");
		assertThat(monthlyRate(m2)).isEqualByComparingTo("10.00");
		assertThat(count(monthlyLogs, m2)).isEqualTo(monthlyBefore);
		assertThat(count(weeklyLogs, a)).isEqualTo(aBefore + 1);
		assertThat(count(weeklyLogs, b)).isEqualTo(bBefore + 1);
	}

	@Test
	void moveToChecklistRequiresNullContribution() throws Exception {
		long a = completedNumeric("2");
		api.expect(patch(a, "\"weeklyGoalId\":" + w1), 422, "$.code", "GOAL_NOT_NUMERIC");
		api.expect(patch(a, "\"weeklyGoalId\":" + w1 + ",\"contributionValue\":3"), 422, "$.code", "GOAL_NOT_NUMERIC");
		assertThat(currentValue(n1)).isEqualByComparingTo("2.00");

		api.expect(patch(a, "\"weeklyGoalId\":" + w1 + ",\"contributionValue\":null"), 200,
				"$.data.goalPath.weeklyGoal.id", (int) w1, "$.data.contributionValue", null);
		// 기록과 이전 주간 목표의 값이 정리되고, 체크형 주간 목표는 완료 1/1이 된다.
		assertThat(currentValue(n1)).isEqualByComparingTo("0.00");
		assertThat(records(n1)).isZero();
		assertThat(weeklyRate(n1)).isEqualByComparingTo("0.00");
		assertThat(weeklyRate(w1)).isEqualByComparingTo("100.00");
		assertThat(monthlyRate(monthly)).isEqualByComparingTo("50.00");
	}

	@Test
	void moveIncompleteChecklistActionRecalculatesBothDenominators() throws Exception {
		long w2 = api.weekly(1L, monthly, "체크 주간 2");
		long done = api.goalAction(1L, w1, "한 일", "");
		long moving = api.goalAction(1L, w1, "옮길 일", "");
		api.expect(api.complete(1L, done, true), 200);
		assertThat(weeklyRate(w1)).isEqualByComparingTo("50.00");

		api.expect(patch(moving, "\"weeklyGoalId\":" + w2), 200);
		// w1은 1/1, w2는 0/1이 된다.
		assertThat(weeklyRate(w1)).isEqualByComparingTo("100.00");
		assertThat(weeklyRate(w2)).isEqualByComparingTo("0.00");
		api.expect(api.get("/api/goals/weekly/" + w2, 1L), 200,
				"$.data.progress.state", "RECORDED", "$.data.actionCounts.total", 1);
	}

	@Test
	void moveToUnavailableWeeklyIs422() throws Exception {
		long a = api.goalAction(1L, w1, "행동", "");
		long other = api.yearly(2L, "남의 연간");
		long otherWeekly = api.weekly(2L, api.monthly(2L, other, "남의 월간"), "남의 주간");
		api.expect(patch(a, "\"weeklyGoalId\":" + otherWeekly), 422,
				"$.code", "GOAL_PARENT_UNAVAILABLE", "$.details.parentLevel", "WEEKLY");
		api.expect(patch(a, "\"weeklyGoalId\":999999"), 422, "$.code", "GOAL_PARENT_UNAVAILABLE");
		assertThat(count("SELECT weekly_goal_id FROM actions WHERE id = ?", a)).isEqualTo((int) w1);
	}

	@Test
	void completedActionCannotMoveToTheFuture() throws Exception {
		long a = api.maintenance(1L, "청소", "");
		api.expect(api.complete(1L, a, true), 200);
		api.expect(patch(a, "\"performedDate\":\"2026-11-13\""), 422,
				"$.code", "ACTION_FUTURE_COMPLETION", "$.details.today", "2026-11-12", "$.details.performedDate", "2026-11-13");
		// 지난 날짜로는 옮길 수 있다.
		api.expect(patch(a, "\"performedDate\":\"2026-11-10\""), 200, "$.data.performedDate", "2026-11-10");
		// 미완료 그 밖의 할 일은 미래 날짜도 된다(날짜 제한이 없다).
		long open = api.maintenance(1L, "미완료", "");
		api.expect(patch(open, "\"performedDate\":\"2026-12-31\""), 200, "$.data.performedDate", "2026-12-31");
	}

	@Test
	void goalActionCannotMoveBeyondCurrentWeek() throws Exception {
		long a = api.goalAction(1L, w1, "행동", "");
		api.expect(patch(a, "\"performedDate\":\"2026-11-15\""), 422,
				"$.code", "ACTION_DATE_OUT_OF_RANGE", "$.details.currentWeekEndDate", "2026-11-14",
				"$.details.performedDate", "2026-11-15");
		api.expect(patch(a, "\"performedDate\":\"2026-11-14\""), 200, "$.data.performedDate", "2026-11-14");
	}

	@Test
	void pastWeekActionCanMoveToTodayKeepingItsWeeklyGoal() throws Exception {
		long past = GoalTestSupport.seedWeekly(jdbc, monthly, LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 7),
				"지난 주 목표", "CHECKLIST", null);
		long a = GoalTestSupport.seedAction(jdbc, 1L, past, "못 한 일", LocalDate.of(2026, 11, 3), false, false);
		api.expect(patch(a, "\"performedDate\":\"2026-11-12\""), 200,
				"$.data.performedDate", "2026-11-12", "$.data.goalPath.weeklyGoal.id", (int) past);
		api.expect(api.get("/api/actions", 1L), 200, "$.data.items.length()", 1, "$.data.pastIncomplete.count", 0);
	}

	@Test
	void typeCannotChange() throws Exception {
		long goal = api.goalAction(1L, w1, "목표 행동", "");
		long maintenance = api.maintenance(1L, "청소", "");
		api.expect(patch(goal, "\"type\":\"MAINTENANCE\""), 400,
				"$.details.fieldErrors[0].field", "type", "$.details.fieldErrors[0].reason", "INVALID_FORMAT");
		api.expect(patch(maintenance, "\"type\":\"GOAL\""), 400, "$.details.fieldErrors[0].field", "type");
		// 같은 값은 무시한다.
		api.expect(patch(goal, "\"type\":\"GOAL\",\"title\":\"같은 유형\""), 200, "$.data.title", "같은 유형");
		// 그 밖의 할 일에는 목표 연결·기여량을 줄 수 없다.
		api.expect(patch(maintenance, "\"weeklyGoalId\":" + w1), 400, "$.details.fieldErrors[0].field", "weeklyGoalId");
		api.expect(patch(maintenance, "\"contributionValue\":1"), 400, "$.details.fieldErrors[0].field", "contributionValue");
	}

	@Test
	void staleVersionIs409WithCurrentVersion() throws Exception {
		long a = api.goalAction(1L, w1, "행동", "");
		api.expect(api.patch(path(a), 1L, "{\"title\":\"먼저\",\"version\":0}"), 200, "$.data.version", 1);
		api.expect(api.patch(path(a), 1L, "{\"title\":\"늦게\",\"version\":0}"), 409,
				"$.code", "VERSION_CONFLICT", "$.details.currentVersion", 1);
		api.expect(api.get("/api/actions", 1L), 200, "$.data.items[0].title", "먼저");
	}

	@Test
	void titlePatchDoesNotRevertCompletion() throws Exception {
		long a = api.goalAction(1L, n1, "문제 풀기", ",\"contributionValue\":2");
		// 수정 화면이 version 0으로 열려 있는 동안 다른 인스턴스가 행동을 완료했다.
		api.expect(api.complete(1L, a, true), 200);
		api.expect(api.patch(path(a), 1L, "{\"title\":\"새 제목\",\"version\":0}"), 200,
				"$.data.title", "새 제목", "$.data.isCompleted", true, "$.data.version", 1);
		assertThat(currentValue(n1)).isEqualByComparingTo("2.00");
		assertThat(records(n1)).isEqualTo(1);
		assertThat(count("SELECT COUNT(*) FROM actions WHERE id = ? AND is_completed = TRUE", a)).isEqualTo(1);
	}

	@Test
	void foreignDeletedAndHiddenPathActionsAre404() throws Exception {
		long a = api.goalAction(1L, w1, "행동", "");
		api.expect(api.patch(path(a), 2L, "{\"title\":\"남의 수정\",\"version\":0}"), 404,
				"$.details.resource", "ACTION");
		api.expect(api.patch(path(999_999), 1L, "{\"title\":\"없음\",\"version\":0}"), 404);
		long deleted = GoalTestSupport.seedAction(jdbc, 1L, null, "삭제됨", LocalDate.of(2026, 11, 12), false, true);
		api.expect(api.patch(path(deleted), 1L, "{\"title\":\"삭제됨\",\"version\":0}"), 404);

		// 경로가 삭제된 행동은 완료 여부와 관계없이 수정할 수 없다.
		long done = GoalTestSupport.seedAction(jdbc, 1L, w1, "완료함", LocalDate.of(2026, 11, 12), true, false);
		jdbc.update("UPDATE weekly_goals SET deleted_at = CURRENT_TIMESTAMP WHERE id = ?", w1);
		api.expect(api.patch(path(a), 1L, "{\"title\":\"숨은 경로\",\"version\":0}"), 404);
		api.expect(api.patch(path(done), 1L, "{\"title\":\"숨은 경로\",\"version\":0}"), 404);
	}
}
