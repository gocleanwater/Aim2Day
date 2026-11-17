package com.aim2day.goal.actions;

import static org.assertj.core.api.Assertions.assertThat;

import com.aim2day.goal.support.GoalTestSupport;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class ActionDeleteApiTests extends ActionTestBase {

	private String path(long id) {
		return "/api/actions/" + id;
	}

	@Test
	void deletingIncompleteChecklistActionRecalculates() throws Exception {
		long done = api.goalAction(1L, w1, "한 일", "");
		long open = api.goalAction(1L, w1, "못 한 일", "");
		api.expect(api.complete(1L, done, true), 200);
		assertThat(weeklyRate(w1)).isEqualByComparingTo("50.00");

		api.expect(api.delete(path(open), 1L), 200, "$.code", "SUCCESS");
		// 분모에서 빠져 1/1이 된다. 월간(w1 100 + n1 0)도 따라 바뀌고 진행 기록이 남는다.
		assertThat(weeklyRate(w1)).isEqualByComparingTo("100.00");
		assertThat(monthlyRate(monthly)).isEqualByComparingTo("50.00");
		assertThat(count("SELECT COUNT(*) FROM goal_progress_logs WHERE target_type = 'WEEKLY_GOAL' AND memo = ?",
				"행동 삭제: 못 한 일")).isEqualTo(1);
		api.expect(api.get("/api/actions", 1L), 200, "$.data.items.length()", 1, "$.data.items[0].id", (int) done);
		api.expect(api.get("/api/goals/weekly/" + w1, 1L), 200,
				"$.data.actionCounts.total", 1, "$.data.actionCounts.completed", 1, "$.data.actions.length()", 1);
		// 이미 삭제한 행동은 다시 지울 수 없다.
		api.expect(api.delete(path(open), 1L), 404, "$.details.resource", "ACTION");
	}

	@Test
	void deletingTheOnlyIncompleteActionReturnsToNoRecord() throws Exception {
		long a = api.goalAction(1L, w1, "유일한 행동", "");
		api.expect(api.delete(path(a), 1L), 200);
		api.expect(api.get("/api/goals/weekly/" + w1, 1L), 200,
				"$.data.progress.state", "NO_RECORD", "$.data.actionCounts.total", 0);
	}

	@Test
	void deletingCompletedActionKeepsProgressAndRecords() throws Exception {
		long a = api.goalAction(1L, n1, "문제 풀기", ",\"contributionValue\":2");
		long c = api.goalAction(1L, w1, "체크 행동", "");
		api.expect(api.complete(1L, a, true), 200);
		api.expect(api.complete(1L, c, true), 200);
		int logs = count("SELECT COUNT(*) FROM goal_progress_logs");

		api.expect(api.delete(path(a), 1L), 200);
		api.expect(api.delete(path(c), 1L), 200);
		// 완료 기록이라 수치 기록·current_value·체크형 분자와 분모가 그대로다. 진행 기록도 늘지 않는다.
		assertThat(currentValue(n1)).isEqualByComparingTo("2.00");
		assertThat(records(n1)).isEqualTo(1);
		assertThat(weeklyRate(n1)).isEqualByComparingTo("20.00");
		assertThat(weeklyRate(w1)).isEqualByComparingTo("100.00");
		assertThat(count("SELECT COUNT(*) FROM goal_progress_logs")).isEqualTo(logs);
		api.expect(api.get("/api/goals/weekly/" + w1, 1L), 200,
				"$.data.actionCounts.total", 1, "$.data.actionCounts.completed", 1,
				"$.data.progress.rate", 100.0, "$.data.actions.length()", 0);
		api.expect(api.get("/api/actions", 1L), 200, "$.data.items.length()", 0);
		// 수치 기록 목록에는 삭제된 행동의 제목이 남는다.
		api.expect(api.get("/api/goals/weekly/" + n1 + "/numeric-records", 1L), 200,
				"$.data.length()", 1, "$.data[0].action.title", "문제 풀기");
		// 삭제한 행동의 완료 취소·수정은 404다(기록까지 없애려면 삭제 전에 완료를 취소해야 한다).
		api.expect(api.complete(1L, a, false), 404);
		assertThat(currentValue(n1)).isEqualByComparingTo("2.00");
	}

	@Test
	void cancelThenDeleteRemovesTheRecord() throws Exception {
		long a = api.goalAction(1L, n1, "문제 풀기", ",\"contributionValue\":2");
		api.expect(api.complete(1L, a, true), 200);
		api.expect(api.complete(1L, a, false), 200);
		api.expect(api.delete(path(a), 1L), 200);
		assertThat(currentValue(n1)).isEqualByComparingTo("0.00");
		assertThat(records(n1)).isZero();
	}

	@Test
	void maintenanceDeleteDoesNotTouchProgress() throws Exception {
		long m = api.maintenance(1L, "청소", "");
		api.expect(api.delete(path(m), 1L), 200);
		api.expect(api.get("/api/actions", 1L), 200, "$.data.items.length()", 0);
		assertThat(count("SELECT COUNT(*) FROM goal_progress_logs")).isZero();
	}

	@Test
	void foreignMissingAndHiddenPathActionsAre404() throws Exception {
		long a = api.goalAction(1L, w1, "행동", "");
		api.expect(api.delete(path(a), 2L), 404, "$.code", "RESOURCE_NOT_FOUND", "$.details.resource", "ACTION");
		api.expect(api.delete(path(999_999), 1L), 404);
		assertThat(count("SELECT COUNT(*) FROM actions WHERE id = ? AND deleted_at IS NULL", a)).isEqualTo(1);

		long done = GoalTestSupport.seedAction(jdbc, 1L, w1, "완료함", LocalDate.of(2026, 11, 12), true, false);
		jdbc.update("UPDATE yearly_goals SET deleted_at = CURRENT_TIMESTAMP WHERE id = ?", yearly);
		api.expect(api.delete(path(a), 1L), 404);
		api.expect(api.delete(path(done), 1L), 404);
		assertThat(count("SELECT COUNT(*) FROM actions WHERE deleted_at IS NOT NULL")).isZero();
	}
}
