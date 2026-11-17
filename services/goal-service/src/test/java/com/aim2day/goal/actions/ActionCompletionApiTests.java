package com.aim2day.goal.actions;

import static org.assertj.core.api.Assertions.assertThat;

import com.aim2day.goal.support.GoalTestSupport;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

class ActionCompletionApiTests extends ActionTestBase {

	private String completionPath(long id) {
		return "/api/actions/" + id + "/completion";
	}

	private List<Map<String, Object>> logs(String targetType, long targetId) {
		return jdbc.queryForList("SELECT previous_rate, new_rate, memo FROM goal_progress_logs "
				+ "WHERE target_type = ? AND target_id = ? ORDER BY id", targetType, targetId);
	}

	@Test
	void completingChecklistActionRecalculatesAndLogs() throws Exception {
		long a = api.goalAction(1L, w1, "스택 정리", "");
		api.goalAction(1L, w1, "큐 정리", "");

		MvcResult result = api.put(completionPath(a), 1L, "{\"completed\":true}");
		api.expect(result, 200,
				"$.data.action.id", (int) a,
				"$.data.action.isCompleted", true,
				"$.data.weeklyGoal.id", (int) w1,
				"$.data.weeklyGoal.progress.state", "RECORDED",
				"$.data.weeklyGoal.progress.rate", 50.0,
				"$.data.weeklyGoal.currentValue", null,
				"$.data.weeklyGoal.isTargetReached", null,
				"$.data.monthlyGoal.id", (int) monthly,
				"$.data.monthlyGoal.progress.state", "RECORDED",
				// 월간 = (w1 50 + n1 0) / 2
				"$.data.monthlyGoal.progress.rate", 25.0,
				// 완료 처리는 행동의 version을 올리지 않는다(열려 있는 수정 화면이 409가 되지 않게).
				"$.data.action.version", 0);
		assertThat((String) GoalTestSupport.read(result, "$.data.action.completedAt")).endsWith("Z");

		assertThat(weeklyRate(w1)).isEqualByComparingTo("50.00");
		assertThat(logs("WEEKLY_GOAL", w1)).hasSize(1);
		Map<String, Object> weeklyLog = logs("WEEKLY_GOAL", w1).get(0);
		assertThat((BigDecimal) weeklyLog.get("PREVIOUS_RATE")).isEqualByComparingTo("0.00");
		assertThat((BigDecimal) weeklyLog.get("NEW_RATE")).isEqualByComparingTo("50.00");
		// 진행 기록 memo의 기본값
		assertThat(weeklyLog.get("MEMO")).isEqualTo("행동 완료: 스택 정리");
		assertThat(logs("MONTHLY_GOAL", monthly)).hasSize(1);
	}

	@Test
	void customMemoGoesToTheProgressLog() throws Exception {
		long a = api.goalAction(1L, w1, "스택 정리", "");
		api.expect(api.put(completionPath(a), 1L, "{\"completed\":true,\"memo\":\"2문제 풀이 완료\"}"), 200);
		assertThat(logs("WEEKLY_GOAL", w1).get(0).get("MEMO")).isEqualTo("2문제 풀이 완료");
		api.expect(api.put(completionPath(a), 1L, "{\"completed\":false}"), 200);
		assertThat(logs("WEEKLY_GOAL", w1).get(1).get("MEMO")).isEqualTo("행동 완료 취소: 스택 정리");
	}

	@Test
	void numericCompletionAddsRecordAndCancelSubtracts() throws Exception {
		long a = api.goalAction(1L, n1, "문제 풀기", ",\"contributionValue\":2");
		MvcResult done = api.put(completionPath(a), 1L, "{\"completed\":true}");
		api.expect(done, 200,
				"$.data.weeklyGoal.currentValue", 2.0,
				"$.data.weeklyGoal.progress.rate", 20.0,
				"$.data.weeklyGoal.isTargetReached", false,
				"$.data.monthlyGoal.progress.rate", 10.0);
		assertThat(currentValue(n1)).isEqualByComparingTo("2.00");
		assertThat(records(n1)).isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT record_source FROM weekly_goal_numeric_records WHERE action_id = ?",
				String.class, a)).isEqualTo("ACTION_COMPLETION");
		// 수치 기록 목록에는 행동 제목이 실린다.
		api.expect(api.get("/api/goals/weekly/" + n1 + "/numeric-records", 1L), 200,
				"$.data[0].source", "ACTION_COMPLETION", "$.data[0].action.id", (int) a,
				"$.data[0].action.title", "문제 풀기", "$.data[0].amount", 2.0);

		api.expect(api.put(completionPath(a), 1L, "{\"completed\":false}"), 200,
				"$.data.action.isCompleted", false, "$.data.action.completedAt", null,
				"$.data.weeklyGoal.currentValue", 0.0, "$.data.weeklyGoal.progress.rate", 0.0);
		assertThat(currentValue(n1)).isEqualByComparingTo("0.00");
		assertThat(records(n1)).isZero();
		// 진행 기록의 이전·다음 값이 이어진다: 0 -> 20 -> 0
		List<Map<String, Object>> weeklyLogs = logs("WEEKLY_GOAL", n1);
		assertThat(weeklyLogs).hasSize(2);
		assertThat((BigDecimal) weeklyLogs.get(0).get("NEW_RATE")).isEqualByComparingTo("20.00");
		assertThat((BigDecimal) weeklyLogs.get(1).get("PREVIOUS_RATE")).isEqualByComparingTo("20.00");
		assertThat((BigDecimal) weeklyLogs.get(1).get("NEW_RATE")).isEqualByComparingTo("0.00");
	}

	@Test
	void numericActionWithoutContributionOnlyChangesState() throws Exception {
		long a = api.goalAction(1L, n1, "기여량 없음", "");
		api.expect(api.put(completionPath(a), 1L, "{\"completed\":true}"), 200,
				"$.data.action.isCompleted", true, "$.data.weeklyGoal.currentValue", 0.0);
		assertThat(records(n1)).isZero();
		assertThat(logs("WEEKLY_GOAL", n1)).isEmpty();
	}

	@Test
	void sameStateIsANoOp() throws Exception {
		long a = api.goalAction(1L, n1, "문제 풀기", ",\"contributionValue\":2");
		api.expect(api.put(completionPath(a), 1L, "{\"completed\":false}"), 200, "$.data.action.isCompleted", false);
		api.expect(api.put(completionPath(a), 1L, "{\"completed\":true}"), 200);
		int logCount = count("SELECT COUNT(*) FROM goal_progress_logs");
		String completedAt = jdbc.queryForObject("SELECT CAST(completed_at AS VARCHAR) FROM actions WHERE id = ?",
				String.class, a);

		// 같은 요청을 반복해도 기록·현재값·진행 기록·완료 시각이 그대로다.
		api.expect(api.put(completionPath(a), 1L, "{\"completed\":true}"), 200,
				"$.data.action.isCompleted", true, "$.data.weeklyGoal.currentValue", 2.0);
		assertThat(records(n1)).isEqualTo(1);
		assertThat(currentValue(n1)).isEqualByComparingTo("2.00");
		assertThat(count("SELECT COUNT(*) FROM goal_progress_logs")).isEqualTo(logCount);
		assertThat(jdbc.queryForObject("SELECT CAST(completed_at AS VARCHAR) FROM actions WHERE id = ?", String.class,
				a)).isEqualTo(completedAt);
	}

	@Test
	void futureActionCannotBeCompletedButCanBeCancelled() throws Exception {
		long future = api.maintenance(1L, "먼 일", ",\"performedDate\":\"2026-12-01\"");
		api.expect(api.put(completionPath(future), 1L, "{\"completed\":true}"), 422,
				"$.code", "ACTION_FUTURE_COMPLETION",
				"$.details.today", "2026-11-12", "$.details.performedDate", "2026-12-01");
		assertThat(count("SELECT COUNT(*) FROM actions WHERE id = ? AND is_completed = TRUE", future)).isZero();

		// 이미 완료된 미래 행동의 완료 취소는 언제든 할 수 있다.
		long doneFuture = GoalTestSupport.seedAction(jdbc, 1L, null, "완료된 미래", LocalDate.of(2026, 12, 2), true, false);
		api.expect(api.put(completionPath(doneFuture), 1L, "{\"completed\":false}"), 200, "$.data.action.isCompleted", false);
		// 오늘과 지난 날짜는 완료할 수 있다(그날 했는데 체크를 잊은 경우).
		long past = GoalTestSupport.seedAction(jdbc, 1L, null, "지난 일", LocalDate.of(2026, 11, 3), false, false);
		api.expect(api.put(completionPath(past), 1L, "{\"completed\":true}"), 200, "$.data.action.performedDate", "2026-11-03");
	}

	@Test
	void maintenanceResponseHasNullGoals() throws Exception {
		long m = api.maintenance(1L, "청소", "");
		api.expect(api.put(completionPath(m), 1L, "{\"completed\":true}"), 200,
				"$.data.action.isCompleted", true, "$.data.weeklyGoal", null, "$.data.monthlyGoal", null);
		// 진행률·진행 기록에는 영향이 없다.
		assertThat(count("SELECT COUNT(*) FROM goal_progress_logs")).isZero();
	}

	@Test
	void validatesBody() throws Exception {
		long a = api.maintenance(1L, "청소", "");
		api.expect(api.put(completionPath(a), 1L, "{}"), 400,
				"$.details.fieldErrors[0].field", "completed", "$.details.fieldErrors[0].reason", "REQUIRED");
		api.expect(api.put(completionPath(a), 1L, "{\"completed\":true,\"memo\":\"" + "가".repeat(256) + "\"}"), 400,
				"$.details.fieldErrors[0].field", "memo", "$.details.fieldErrors[0].reason", "TOO_LONG");
		api.expect(api.put(completionPath(a), 1L, "{\"completed\":\"yes\"}"), 400, "$.code", "MALFORMED_REQUEST");
	}

	@Test
	void foreignOrMissingActionIs404() throws Exception {
		long a = api.maintenance(1L, "청소", "");
		api.expect(api.put(completionPath(a), 2L, "{\"completed\":true}"), 404,
				"$.code", "RESOURCE_NOT_FOUND", "$.details.resource", "ACTION", "$.details.id", (int) a);
		api.expect(api.put(completionPath(999_999), 1L, "{\"completed\":true}"), 404);
		long deleted = GoalTestSupport.seedAction(jdbc, 1L, null, "삭제됨", LocalDate.of(2026, 11, 12), false, true);
		api.expect(api.put(completionPath(deleted), 1L, "{\"completed\":true}"), 404);
	}

	@Test
	void actionWithDeletedPathIs404EvenWhenCompleted() throws Exception {
		long open = api.goalAction(1L, w1, "미완료", "");
		long done = api.goalAction(1L, w1, "완료", "");
		api.expect(api.complete(1L, done, true), 200);
		BigDecimal before = weeklyRate(w1);

		// 월간 목표가 삭제되면 경로의 일부라 완료 여부와 관계없이 404다(숨은 부모의 진행률을 바꾸지 않는다).
		jdbc.update("UPDATE monthly_goals SET deleted_at = CURRENT_TIMESTAMP WHERE id = ?", monthly);
		api.expect(api.complete(1L, open, true), 404, "$.details.resource", "ACTION");
		api.expect(api.complete(1L, done, false), 404, "$.details.resource", "ACTION");
		assertThat(weeklyRate(w1)).isEqualByComparingTo(before);
		assertThat(count("SELECT COUNT(*) FROM actions WHERE id = ? AND is_completed = TRUE", done)).isEqualTo(1);
	}

	@Test
	void overflowingTheCurrentValueIs400() throws Exception {
		long big = api.numericWeekly(1L, monthly, "큰 목표", "99999999.99");
		jdbc.update("UPDATE weekly_goals SET current_value = 99999999.00 WHERE id = ?", big);
		long a = api.goalAction(1L, big, "넘치는 기여", ",\"contributionValue\":2");
		api.expect(api.complete(1L, a, true), 400,
				"$.details.fieldErrors[0].field", "contributionValue", "$.details.fieldErrors[0].reason", "OUT_OF_RANGE");
		assertThat(count("SELECT COUNT(*) FROM actions WHERE id = ? AND is_completed = TRUE", a)).isZero();
		assertThat(currentValue(big)).isEqualByComparingTo("99999999.00");
	}

	@Test
	void concurrentDifferentActionsOnSameWeeklyKeepBothContributions() throws Exception {
		// 서로 다른 행동 2(기여 2)와 1(기여 1)을 연달아 완료 -> 합계 3(ERD 결정 16 예시). 동시 실행은 ActionCompletionConcurrencyTests.
		long a = api.goalAction(1L, n1, "행동 A", ",\"contributionValue\":2");
		long b = api.goalAction(1L, n1, "행동 B", ",\"contributionValue\":1");
		api.expect(api.complete(1L, a, true), 200);
		api.expect(api.complete(1L, b, true), 200, "$.data.weeklyGoal.currentValue", 3.0,
				"$.data.weeklyGoal.progress.rate", 30.0);
		assertThat(records(n1)).isEqualTo(2);
	}
}
