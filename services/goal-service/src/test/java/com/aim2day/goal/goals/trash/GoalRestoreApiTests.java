package com.aim2day.goal.goals.trash;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class GoalRestoreApiTests extends TrashTestBase {

	@Test
	void yearlyRestoreBringsChildrenBackAndReturnsDetailShape() throws Exception {
		api.goalAction(1L, w1, "미완료", "");
		api.expect(delete(1L, "yearly", yearly, true), 200);

		api.expect(restore(1L, "yearly", yearly), 200,
				"$.code", "SUCCESS", "$.data.goal.id", (int) yearly, "$.data.goal.title", "연간",
				"$.data.monthlyGoals.length()", 1, "$.data.monthlyGoals[0].id", (int) monthly,
				"$.data.monthlyGoals[0].weeklyGoals.length()", 2);
		assertThat(isDeleted("yearly_goals", yearly)).isFalse();
		// 숨겨졌던 미완료 행동이 원래 연결대로 다시 보인다.
		api.expect(api.get("/api/goals/weekly/" + w1, 1L), 200, "$.data.actions.length()", 1);
		api.expect(api.get("/api/goals/trash", 1L), 200, "$.data.length()", 0);
	}

	@Test
	void separatelyDeletedChildStaysDeleted() throws Exception {
		api.expect(delete(1L, "weekly", n1, false), 200);
		api.expect(delete(1L, "yearly", yearly, true), 200);
		api.expect(restore(1L, "yearly", yearly), 200, "$.data.monthlyGoals[0].weeklyGoals.length()", 1,
				"$.data.monthlyGoals[0].weeklyGoals[0].id", (int) w1);
		api.expect(api.get("/api/goals/weekly/" + n1, 1L), 404);
		// 따로 삭제된 n1은 휴지통에 남아 있고, 이제 부모가 살아 있어 복원할 수 있다.
		api.expect(api.get("/api/goals/trash", 1L), 200, "$.data.length()", 1, "$.data[0].id", (int) n1,
				"$.data[0].isParentDeleted", false);
		api.expect(restore(1L, "weekly", n1), 200, "$.data.id", (int) n1);
	}

	@Test
	void monthlyRestoreReturnsMonthlyDetail() throws Exception {
		api.expect(delete(1L, "monthly", monthly, true), 200);
		api.expect(restore(1L, "monthly", monthly), 200, "$.data.id", (int) monthly, "$.data.title", "월간",
				"$.data.weeklyGoals.length()", 2);
		api.expect(api.get("/api/goals/weekly/" + w1, 1L), 200);
	}

	@Test
	void weeklyRestoreRecalculatesMonthlyAndReturnsWeeklyDetail() throws Exception {
		long a = api.goalAction(1L, w1, "한 일", "");
		api.expect(api.complete(1L, a, true), 200);
		api.goalAction(1L, w1, "남은 일", "");
		// w1 50%, n1 0% -> 월간 25%
		assertThat(monthlyRate(monthly)).isEqualByComparingTo(new BigDecimal("25.00"));
		api.expect(delete(1L, "weekly", w1, true), 200);
		assertThat(monthlyRate(monthly)).isEqualByComparingTo("0.00");

		api.expect(restore(1L, "weekly", w1), 200, "$.data.id", (int) w1, "$.data.actions.length()", 2,
				"$.data.progress.rate", 50.0);
		assertThat(monthlyRate(monthly)).isEqualByComparingTo("25.00");
		assertThat(count("SELECT COUNT(*) FROM goal_progress_logs WHERE target_type = 'MONTHLY_GOAL' AND memo = ?",
				"주간 목표 복원: 체크 주간")).isEqualTo(1);
	}

	@Test
	void notDeletedForeignAndMissingGoalsAre404() throws Exception {
		api.expect(restore(1L, "yearly", yearly), 404, "$.code", "RESOURCE_NOT_FOUND");
		api.expect(delete(1L, "weekly", w1, false), 200);
		api.expect(restore(2L, "weekly", w1), 404, "$.details.resource", "WEEKLY_GOAL");
		api.expect(restore(1L, "weekly", 999_999), 404);
		api.expect(restore(1L, "daily", w1), 404);
		// 복원한 뒤에는 삭제 상태가 아니므로 다시 복원할 수 없다.
		api.expect(restore(1L, "weekly", w1), 200);
		api.expect(restore(1L, "weekly", w1), 404);
	}

	@Test
	void expiredRestoreIsRejectedEvenBeforePurge() throws Exception {
		api.expect(delete(1L, "weekly", w1, false), 200);
		// 기한(12-12T03:00:00Z) 정각까지는 복원할 수 있다.
		clockAt("2026-12-12T03:00:00Z");
		api.expect(restore(1L, "weekly", w1), 200);

		api.expect(delete(1L, "weekly", w1, false), 200, "$.data.deletedAt", "2026-12-12T03:00:00Z");
		clockAt("2027-01-11T03:00:01Z");
		api.expect(restore(1L, "weekly", w1), 409, "$.code", "GOAL_RESTORE_EXPIRED",
				"$.details.deletedAt", "2026-12-12T03:00:00Z", "$.details.restoreDeadline", "2027-01-11T03:00:00Z");
		assertThat(isDeleted("weekly_goals", w1)).isTrue();
	}

	@Test
	void parentDeletedIsRejectedForEachLevel() throws Exception {
		api.expect(delete(1L, "weekly", w1, false), 200);
		api.expect(delete(1L, "monthly", monthly, true), 200);
		// 주간: 부모 월간이 삭제 상태
		api.expect(restore(1L, "weekly", w1), 409, "$.code", "GOAL_PARENT_DELETED",
				"$.details.parentLevel", "MONTHLY", "$.details.parentId", (int) monthly);
		api.expect(delete(1L, "yearly", yearly, true), 200);
		// 월간: 부모 연간이 삭제 상태
		api.expect(restore(1L, "monthly", monthly), 409, "$.code", "GOAL_PARENT_DELETED",
				"$.details.parentLevel", "YEARLY", "$.details.parentId", (int) yearly);
		// 주간: 가장 가까운 삭제 조상(월간)을 알려 준다.
		api.expect(restore(1L, "weekly", w1), 409, "$.details.parentLevel", "MONTHLY");
		// 위에서부터 복원하면 차례로 성공한다.
		api.expect(restore(1L, "yearly", yearly), 200);
		api.expect(restore(1L, "monthly", monthly), 200);
		api.expect(restore(1L, "weekly", w1), 200);
	}

	@Test
	void weeklyWithDeletedGrandparentOnlyReportsYearly() throws Exception {
		api.expect(delete(1L, "weekly", w1, false), 200);
		api.expect(delete(1L, "yearly", yearly, true), 200);
		api.expect(restore(1L, "weekly", w1), 409, "$.code", "GOAL_PARENT_DELETED",
				"$.details.parentLevel", "YEARLY", "$.details.parentId", (int) yearly);
	}

	@Test
	void checksRunInOrderExpiredBeforeParentDeletedBeforeLimit() throws Exception {
		// 기한 지남 + 부모 삭제: 기한이 먼저다.
		api.expect(delete(1L, "weekly", w1, false), 200);
		api.expect(delete(1L, "monthly", monthly, true), 200);
		clockAt("2026-12-20T03:00:00Z");
		api.expect(restore(1L, "weekly", w1), 409, "$.code", "GOAL_RESTORE_EXPIRED");
		clockAt("2026-11-12T03:00:00Z");
		api.expect(restore(1L, "weekly", w1), 409, "$.code", "GOAL_PARENT_DELETED");
	}

	@Test
	void yearlyRestoreRechecksLimit() throws Exception {
		api.expect(delete(1L, "yearly", yearly, true), 200);
		api.yearly(1L, "새1");
		api.yearly(1L, "새2");
		api.yearly(1L, "새3");
		api.expect(restore(1L, "yearly", yearly), 409, "$.code", "GOAL_LIMIT_EXCEEDED",
				"$.details.year", 2026, "$.details.current", 3, "$.details.max", 3);
		assertThat(isDeleted("yearly_goals", yearly)).isTrue();

		// 기한이 지났으면 한도보다 기한이 먼저다.
		clockAt("2026-12-20T03:00:00Z");
		api.expect(restore(1L, "yearly", yearly), 409, "$.code", "GOAL_RESTORE_EXPIRED");
	}

	@Test
	void yearlyRestoreSucceedsWhenRoomExists() throws Exception {
		api.expect(delete(1L, "yearly", yearly, true), 200);
		api.yearly(1L, "새1");
		api.yearly(1L, "새2");
		api.expect(restore(1L, "yearly", yearly), 200);
		api.expect(api.get("/api/goals/yearly", 1L), 200, "$.data.items.length()", 3);
	}
}
