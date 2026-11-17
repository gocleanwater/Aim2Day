package com.aim2day.goal.actions;

import com.aim2day.goal.support.GoalTestSupport;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** 직전 주 요약. 시계 2026-11-12(목)이고 주 시작이 일요일이라 현재 주는 11-08~11-14, 직전 주는 11-01~11-07이다. */
class PreviousSummaryApiTests extends ActionTestBase {

	private static final String PATH = "/api/goals/weekly/previous-summary";

	private long prevWeekly(String title, LocalDate start, LocalDate end) {
		return GoalTestSupport.seedWeekly(jdbc, monthly, start, end, title, "CHECKLIST", null);
	}

	@Test
	void emptyWhenNoWeeklyGoalTouchesThePreviousWeek() throws Exception {
		api.expect(api.get(PATH, 1L), 200,
				"$.data.startDate", "2026-11-01", "$.data.endDate", "2026-11-07", "$.data.weeklyGoals.length()", 0);
	}

	@Test
	void splitsCompletedAndRemainingActionsByDateThenId() throws Exception {
		long prev = prevWeekly("자료구조 정리", LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 7));
		long later = GoalTestSupport.seedAction(jdbc, 1L, prev, "큐 정리", LocalDate.of(2026, 11, 5), false, false);
		long first = GoalTestSupport.seedAction(jdbc, 1L, prev, "스택 정리", LocalDate.of(2026, 11, 2), true, false);
		long tie = GoalTestSupport.seedAction(jdbc, 1L, prev, "트리 정리", LocalDate.of(2026, 11, 5), false, false);
		// 삭제한 행동은 빠진다.
		GoalTestSupport.seedAction(jdbc, 1L, prev, "삭제함", LocalDate.of(2026, 11, 3), true, true);
		// 이번 주 주간 목표(w1)는 직전 주와 겹치지 않아 들어가지 않는다.
		jdbc.update("UPDATE weekly_goals SET progress_rate = 50.00 WHERE id = ?", prev);

		api.expect(api.get(PATH, 1L), 200,
				"$.data.weeklyGoals.length()", 1,
				"$.data.weeklyGoals[0].id", (int) prev,
				"$.data.weeklyGoals[0].title", "자료구조 정리",
				"$.data.weeklyGoals[0].path.yearlyGoal.id", (int) yearly,
				"$.data.weeklyGoals[0].path.yearlyGoal.title", "연간",
				"$.data.weeklyGoals[0].path.monthlyGoal.id", (int) monthly,
				"$.data.weeklyGoals[0].path.monthlyGoal.title", "월간",
				"$.data.weeklyGoals[0].progress.state", "RECORDED",
				"$.data.weeklyGoals[0].progress.rate", 50.0,
				"$.data.weeklyGoals[0].completedActions.length()", 1,
				"$.data.weeklyGoals[0].completedActions[0].id", (int) first,
				"$.data.weeklyGoals[0].completedActions[0].title", "스택 정리",
				"$.data.weeklyGoals[0].completedActions[0].performedDate", "2026-11-02",
				"$.data.weeklyGoals[0].remainingActions.length()", 2,
				"$.data.weeklyGoals[0].remainingActions[0].id", (int) later,
				"$.data.weeklyGoals[0].remainingActions[1].id", (int) tie);
	}

	@Test
	void includesWeeklyGoalsThatOverlapByOneDayAndKeepsNoRecordState() throws Exception {
		// 주 시작 요일이 바뀌기 전에 만든 주간 목표(10-31~11-06)도 직전 주와 겹친다.
		long overlap = prevWeekly("걸친 주", LocalDate.of(2026, 10, 31), LocalDate.of(2026, 11, 6));
		long touchesFirstDay = prevWeekly("마지막 날만", LocalDate.of(2026, 11, 7), LocalDate.of(2026, 11, 13));
		prevWeekly("겹치지 않음", LocalDate.of(2026, 10, 24), LocalDate.of(2026, 10, 30));
		api.expect(api.get(PATH, 1L), 200,
				"$.data.weeklyGoals.length()", 2,
				"$.data.weeklyGoals[0].id", (int) overlap,
				"$.data.weeklyGoals[1].id", (int) touchesFirstDay,
				// 연결 행동이 없는 체크형은 기록 없음이다.
				"$.data.weeklyGoals[0].progress.state", "NO_RECORD",
				"$.data.weeklyGoals[0].progress.rate", null,
				"$.data.weeklyGoals[0].completedActions.length()", 0,
				"$.data.weeklyGoals[0].remainingActions.length()", 0);
	}

	@Test
	void numericWeeklyIsAlwaysRecorded() throws Exception {
		long numeric = GoalTestSupport.seedWeekly(jdbc, monthly, LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 7),
				"수치 주", "NUMERIC", new java.math.BigDecimal("10.00"));
		api.expect(api.get(PATH, 1L), 200,
				"$.data.weeklyGoals[0].id", (int) numeric, "$.data.weeklyGoals[0].progress.state", "RECORDED",
				"$.data.weeklyGoals[0].progress.rate", 0.0);
	}

	@Test
	void hiddenAndForeignWeeklyGoalsAreExcluded() throws Exception {
		long hiddenByAncestor = prevWeekly("조상 삭제", LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 7));
		long deleted = prevWeekly("삭제됨", LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 7));
		jdbc.update("UPDATE weekly_goals SET deleted_at = CURRENT_TIMESTAMP WHERE id = ?", deleted);
		long otherYearly = GoalTestSupport.seedYearly(jdbc, 2L, 2026, "남의 연간");
		long otherMonthly = GoalTestSupport.seedMonthly(jdbc, otherYearly, LocalDate.of(2026, 11, 1),
				LocalDate.of(2026, 11, 30), "남의 월간");
		GoalTestSupport.seedWeekly(jdbc, otherMonthly, LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 7), "남의 주",
				"CHECKLIST", null);

		api.expect(api.get(PATH, 1L), 200, "$.data.weeklyGoals.length()", 1,
				"$.data.weeklyGoals[0].id", (int) hiddenByAncestor);
		// 조상(연간)이 삭제되면 하위 주간 목표는 요약에서 빠진다.
		jdbc.update("UPDATE yearly_goals SET deleted_at = CURRENT_TIMESTAMP WHERE id = ?", yearly);
		api.expect(api.get(PATH, 1L), 200, "$.data.weeklyGoals.length()", 0);
		// 다른 사용자에게는 자기 목표만 보인다.
		api.expect(api.get(PATH, 2L), 200, "$.data.weeklyGoals.length()", 1, "$.data.weeklyGoals[0].title", "남의 주");
	}

	@Test
	void followsWeekStartSetting() throws Exception {
		// 월요일 시작이면 현재 주는 11-09~11-15, 직전 주는 11-02~11-08이다.
		api.expect(api.put("/api/settings", 1L, "{\"weekStartDay\":\"MONDAY\",\"timezone\":\"Asia/Seoul\",\"version\":0}"),
				200);
		api.expect(api.get(PATH, 1L), 200, "$.data.startDate", "2026-11-02", "$.data.endDate", "2026-11-08");
	}
}
