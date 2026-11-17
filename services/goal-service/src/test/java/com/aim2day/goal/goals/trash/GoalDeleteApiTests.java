package com.aim2day.goal.goals.trash;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class GoalDeleteApiTests extends TrashTestBase {

	@Test
	void yearlyWithChildrenNeedsConfirmAndReportsCounts() throws Exception {
		api.goalAction(1L, w1, "미완료 A", "");
		long done = api.goalAction(1L, w1, "완료함", "");
		api.goalAction(1L, n1, "미완료 B", ",\"contributionValue\":1");
		api.expect(api.complete(1L, done, true), 200);

		api.expect(delete(1L, "yearly", yearly, false), 409,
				"$.code", "GOAL_DELETE_CONFIRM_REQUIRED",
				"$.message", "하위 목표와 미완료 행동이 함께 숨겨집니다. 삭제할까요?",
				"$.details.monthlyGoalCount", 1, "$.details.weeklyGoalCount", 2,
				"$.details.incompleteActionCount", 2,
				"$.details.restoreDeadline", "2026-12-12T03:00:00Z");
		var body = api.body(delete(1L, "yearly", yearly, false));
		assertThat(body).contains("\"notice\"").contains("과거 이력으로 남습니다");
		// 거부된 삭제는 아무것도 바꾸지 않는다.
		assertThat(isDeleted("yearly_goals", yearly)).isFalse();
		api.expect(api.get("/api/goals/yearly/" + yearly, 1L), 200);
	}

	@Test
	void confirmedDeleteHidesWholeBranchWithoutTouchingChildRows() throws Exception {
		long open = api.goalAction(1L, w1, "미완료", "");
		long done = api.goalAction(1L, w1, "완료함", "");
		api.expect(api.complete(1L, done, true), 200);

		api.expect(delete(1L, "yearly", yearly, true), 200,
				"$.code", "SUCCESS", "$.data.deletedAt", "2026-11-12T03:00:00Z",
				"$.data.restoreDeadline", "2026-12-12T03:00:00Z");
		assertThat(isDeleted("yearly_goals", yearly)).isTrue();
		// 하위 행은 바꾸지 않는다(숨김은 조상의 deleted_at 때문이다).
		assertThat(isDeleted("monthly_goals", monthly)).isFalse();
		assertThat(isDeleted("weekly_goals", w1)).isFalse();
		assertThat(count("SELECT COUNT(*) FROM actions WHERE deleted_at IS NOT NULL")).isZero();

		api.expect(api.get("/api/goals/yearly/" + yearly, 1L), 404);
		api.expect(api.get("/api/goals/monthly/" + monthly, 1L), 404);
		api.expect(api.get("/api/goals/weekly/" + w1, 1L), 404);
		// 미완료 행동은 숨고 완료 행동은 과거 이력으로 목록에 남는다. 숨은 행동의 쓰기는 404다.
		api.expect(api.get("/api/actions", 1L), 200, "$.data.items.length()", 1, "$.data.items[0].id", (int) done);
		api.expect(api.complete(1L, open, true), 404);
	}

	@Test
	void goalWithoutVisibleChildrenDeletesWithoutConfirm() throws Exception {
		long lonely = api.yearly(1L, "빈 연간");
		api.expect(delete(1L, "yearly", lonely, false), 200, "$.data.restoreDeadline", "2026-12-12T03:00:00Z");

		// 완료된 행동만 있는 주간 목표도 숨겨질 미완료 행동이 없으므로 확인 없이 삭제된다.
		long done = api.goalAction(1L, w1, "완료함", "");
		api.expect(api.complete(1L, done, true), 200);
		api.expect(delete(1L, "weekly", w1, false), 200);
		assertThat(isDeleted("weekly_goals", w1)).isTrue();
	}

	@Test
	void monthlyReportsWeeklyCountsWithZeroMonthlyCount() throws Exception {
		api.goalAction(1L, w1, "미완료", "");
		api.expect(delete(1L, "monthly", monthly, false), 409,
				"$.details.monthlyGoalCount", 0, "$.details.weeklyGoalCount", 2,
				"$.details.incompleteActionCount", 1);
		api.expect(delete(1L, "monthly", monthly, true), 200);
		assertThat(isDeleted("monthly_goals", monthly)).isTrue();
		assertThat(isDeleted("yearly_goals", yearly)).isFalse();
		api.expect(api.get("/api/goals/weekly/" + w1, 1L), 404);
	}

	@Test
	void weeklyWithIncompleteActionsNeedsConfirm() throws Exception {
		api.goalAction(1L, w1, "미완료 1", "");
		api.goalAction(1L, w1, "미완료 2", "");
		api.expect(delete(1L, "weekly", w1, false), 409,
				"$.code", "GOAL_DELETE_CONFIRM_REQUIRED", "$.details.monthlyGoalCount", 0,
				"$.details.weeklyGoalCount", 0, "$.details.incompleteActionCount", 2);
		api.expect(delete(1L, "weekly", w1, true), 200);
	}

	@Test
	void alreadyHiddenChildrenAreNotCountedAgain() throws Exception {
		api.goalAction(1L, w1, "숨겨질 행동", "");
		api.expect(delete(1L, "weekly", w1, true), 200);
		// w1은 따로 삭제돼 이미 숨어 있다. 월간 삭제 때 센 하위는 n1 하나뿐이고 w1의 행동은 세지 않는다.
		api.expect(delete(1L, "monthly", monthly, false), 409,
				"$.details.weeklyGoalCount", 1, "$.details.incompleteActionCount", 0);
	}

	@Test
	void weeklyDeleteRecalculatesMonthlyAndLogsMemo() throws Exception {
		long a = api.goalAction(1L, w1, "한 일", "");
		api.expect(api.complete(1L, a, true), 200);
		// w1 100%, n1 0% -> 월간 50%
		assertThat(monthlyRate(monthly)).isEqualByComparingTo("50.00");

		api.expect(delete(1L, "weekly", w1, false), 200);
		// 삭제된 주간은 분모에서 빠져 n1(0%)만 남는다.
		assertThat(monthlyRate(monthly)).isEqualByComparingTo("0.00");
		assertThat(count("SELECT COUNT(*) FROM goal_progress_logs WHERE target_type = 'MONTHLY_GOAL' AND memo = ?",
				"주간 목표 삭제: 체크 주간")).isEqualTo(1);
		api.expect(api.get("/api/goals/monthly/" + monthly, 1L), 200,
				"$.data.weeklyGoalCount", 1, "$.data.progress.rate", 0.0);
	}

	@Test
	void foreignMissingDeletedAndHiddenGoalsAre404() throws Exception {
		api.expect(delete(2L, "yearly", yearly, true), 404, "$.code", "RESOURCE_NOT_FOUND",
				"$.details.resource", "YEARLY_GOAL");
		api.expect(delete(2L, "monthly", monthly, true), 404, "$.details.resource", "MONTHLY_GOAL");
		api.expect(delete(2L, "weekly", w1, true), 404, "$.details.resource", "WEEKLY_GOAL");
		api.expect(delete(1L, "weekly", 999_999, true), 404);
		api.expect(delete(1L, "daily", w1, true), 404);
		assertThat(isDeleted("yearly_goals", yearly)).isFalse();

		api.expect(delete(1L, "weekly", w1, true), 200);
		// 이미 삭제됐으면 다시 삭제할 수 없다. 조상이 삭제돼 숨은 목표도 마찬가지다.
		api.expect(delete(1L, "weekly", w1, true), 404);
		api.expect(delete(1L, "yearly", yearly, true), 200);
		api.expect(delete(1L, "monthly", monthly, true), 404);
		api.expect(delete(1L, "weekly", n1, true), 404);
	}

	@Test
	void unauthenticatedIsRejected() throws Exception {
		var result = mockMvc.perform(
				org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(
						"/api/goals/yearly/" + yearly + "?confirm=true")).andReturn();
		assertThat(result.getResponse().getStatus()).isIn(401, 403);
	}

	@Test
	void deleteResponseMatchesTrashListWhenClockHasFractionalSeconds() throws Exception {
		clockAt("2026-11-12T10:00:00.734Z");
		api.expect(delete(1L, "weekly", w1, false), 200,
				"$.data.deletedAt", "2026-11-12T10:00:00Z", "$.data.restoreDeadline", "2026-12-12T10:00:00Z");
		api.expect(api.get("/api/goals/trash", 1L), 200,
				"$.data[0].deletedAt", "2026-11-12T10:00:00Z", "$.data[0].restoreDeadline", "2026-12-12T10:00:00Z");
		// 기한 시각의 소수 초(10:00:00.900)도 초 단위로 잘라 비교하므로 기한 정각과 같아 복원할 수 있다.
		clockAt("2026-12-12T10:00:00.900Z");
		api.expect(restore(1L, "weekly", w1), 200);
	}
}
