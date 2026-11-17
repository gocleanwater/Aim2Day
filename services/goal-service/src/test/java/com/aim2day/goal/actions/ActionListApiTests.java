package com.aim2day.goal.actions;

import static org.assertj.core.api.Assertions.assertThat;

import com.aim2day.goal.support.GoalTestSupport;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

class ActionListApiTests extends ActionTestBase {

	private static final LocalDate TODAY = LocalDate.of(2026, 11, 12);

	@Test
	void summaryClassificationAndPastIncomplete() throws Exception {
		long g1 = api.goalAction(1L, w1, "목표 행동 1", "");
		api.goalAction(1L, w1, "목표 행동 2", "");
		long m1 = api.maintenance(1L, "청소", "");
		api.expect(api.complete(1L, g1, true), 200);
		api.expect(api.complete(1L, m1, true), 200);
		// 오늘이 아닌 날짜의 행동은 목록과 요약에 들어가지 않는다.
		api.maintenance(1L, "내일 할 일", ",\"performedDate\":\"2026-11-13\"");

		api.expect(api.get("/api/actions", 1L), 200,
				"$.data.date", "2026-11-12",
				"$.data.sort", "IMPORTANCE_URGENCY",
				"$.data.items.length()", 3,
				"$.data.summary.goal.total", 2,
				"$.data.summary.goal.completed", 1,
				"$.data.summary.maintenance.total", 1,
				"$.data.summary.maintenance.completed", 1,
				// 사분면 분류 전에는 모두 미분류이고 확정한 변경이 없다.
				"$.data.classification.unclassified", 3,
				"$.data.classification.unconfirmedChanges", 0,
				"$.data.classification.confirmed", 0,
				"$.data.classification.confirmedToday", false,
				"$.data.pastIncomplete.count", 0,
				"$.data.pastIncomplete.items.length()", 0);
	}

	@Test
	void pastIncompleteOnlyForTodayOrderedByDateThenId() throws Exception {
		long late = GoalTestSupport.seedAction(jdbc, 1L, w1, "나중 것", LocalDate.of(2026, 11, 10), false, false);
		long early = GoalTestSupport.seedAction(jdbc, 1L, null, "먼저 것", LocalDate.of(2026, 11, 3), false, false);
		long sameDay = GoalTestSupport.seedAction(jdbc, 1L, null, "같은 날 뒤", LocalDate.of(2026, 11, 10), false, false);
		// 완료·삭제된 행동과 다른 사용자의 행동은 들어가지 않는다.
		GoalTestSupport.seedAction(jdbc, 1L, null, "이미 함", LocalDate.of(2026, 11, 9), true, false);
		GoalTestSupport.seedAction(jdbc, 1L, null, "삭제함", LocalDate.of(2026, 11, 9), false, true);
		GoalTestSupport.seedAction(jdbc, 2L, null, "남의 것", LocalDate.of(2026, 11, 9), false, false);

		api.expect(api.get("/api/actions", 1L), 200,
				"$.data.items.length()", 0,
				"$.data.pastIncomplete.count", 3,
				"$.data.pastIncomplete.items[0].id", (int) early,
				"$.data.pastIncomplete.items[1].id", (int) late,
				"$.data.pastIncomplete.items[2].id", (int) sameDay,
				"$.data.pastIncomplete.items[0].placement.state", "UNCLASSIFIED");
		api.expect(api.get("/api/actions?date=2026-11-12", 1L), 200, "$.data.pastIncomplete.count", 3);
		// 오늘이 아닌 날짜는 null이다.
		api.expect(api.get("/api/actions?date=2026-11-11", 1L), 200,
				"$.data.date", "2026-11-11", "$.data.pastIncomplete", null);
		api.expect(api.get("/api/actions?date=2026-11-13", 1L), 200, "$.data.pastIncomplete", null);
	}

	@Test
	void dateParameterSelectsPerformedDate() throws Exception {
		GoalTestSupport.seedAction(jdbc, 1L, null, "어제", LocalDate.of(2026, 11, 11), true, false);
		api.expect(api.get("/api/actions?date=2026-11-11", 1L), 200,
				"$.data.items.length()", 1, "$.data.items[0].title", "어제", "$.data.summary.maintenance.completed", 1);
		api.expect(api.get("/api/actions", 1L), 200, "$.data.items.length()", 0);
		api.expect(api.get("/api/actions?date=not-a-date", 1L), 400, "$.code", "VALIDATION_FAILED");
	}

	@Test
	void sortVariants() throws Exception {
		long m1 = api.maintenance(1L, "청소", "");
		long g1 = api.goalAction(1L, w1, "공부", "");
		long m2 = api.maintenance(1L, "빨래", "");
		long g2 = api.goalAction(1L, w1, "복습", "");
		// 사분면 분류 전에는 좌표 정렬 세 가지가 모두 id 오름차순이다.
		for (String sort : new String[] {"IMPORTANCE_URGENCY", "IMPORTANCE", "URGENCY"}) {
			api.expect(api.get("/api/actions?sort=" + sort, 1L), 200,
					"$.data.sort", sort,
					"$.data.items[0].id", (int) m1, "$.data.items[1].id", (int) g1,
					"$.data.items[2].id", (int) m2, "$.data.items[3].id", (int) g2);
		}
		// 기본값은 IMPORTANCE_URGENCY이고, GROUP은 GOAL 먼저이며 그룹 안은 id 오름차순이다.
		api.expect(api.get("/api/actions?sort=GROUP", 1L), 200,
				"$.data.items[0].id", (int) g1, "$.data.items[1].id", (int) g2,
				"$.data.items[2].id", (int) m1, "$.data.items[3].id", (int) m2);
		api.expect(api.get("/api/actions?sort=BOGUS", 1L), 400, "$.code", "VALIDATION_FAILED");
	}

	@Test
	void sorterUsesDisplayedPlacementAndPutsUnclassifiedLast() {
		// 정렬기는 placement.displayed를 쓴다(사분면 분류는 단위 5). 좌표가 있는 행동을 직접 만들어 규칙을 확인한다.
		var items = java.util.List.of(
				placed(1, 10, 10), placed(2, 80, 20), placed(3, 50, 60), unplaced(4), placed(5, 80, 20));
		assertThat(ids(ActionSorter.sort(items, ActionSort.IMPORTANCE_URGENCY))).containsExactly(3L, 2L, 5L, 1L, 4L);
		assertThat(ids(ActionSorter.sort(items, ActionSort.IMPORTANCE))).containsExactly(2L, 5L, 3L, 1L, 4L);
		assertThat(ids(ActionSorter.sort(items, ActionSort.URGENCY))).containsExactly(3L, 2L, 5L, 1L, 4L);
	}

	private static java.util.List<Long> ids(java.util.List<com.aim2day.goal.actions.dto.ActionResponse> sorted) {
		return sorted.stream().map(com.aim2day.goal.actions.dto.ActionResponse::id).toList();
	}

	private static com.aim2day.goal.actions.dto.ActionResponse placed(long id, int x, int y) {
		var coordinate = new com.aim2day.goal.actions.dto.ActionResponse.Coordinate(x, y);
		return response(id, new com.aim2day.goal.actions.dto.ActionResponse.Placement("CONFIRMED", coordinate, null,
				coordinate));
	}

	private static com.aim2day.goal.actions.dto.ActionResponse unplaced(long id) {
		return response(id, com.aim2day.goal.actions.dto.ActionResponse.Placement.unclassified());
	}

	private static com.aim2day.goal.actions.dto.ActionResponse response(long id,
			com.aim2day.goal.actions.dto.ActionResponse.Placement placement) {
		return new com.aim2day.goal.actions.dto.ActionResponse(id, ActionType.GOAL, "t", TODAY, null, null, false,
				null, null, null, null, null, "#3B82F6", placement, 0);
	}

	@Test
	void daysUntilDueIsRelativeToToday() throws Exception {
		api.goalAction(1L, w1, "이틀 뒤", ",\"dueDate\":\"2026-11-14\"");
		api.goalAction(1L, w1, "오늘", ",\"dueDate\":\"2026-11-12\"");
		api.goalAction(1L, w1, "지남", ",\"dueDate\":\"2026-11-10\"");
		api.goalAction(1L, w1, "기한 없음", "");
		api.expect(api.get("/api/actions", 1L), 200,
				"$.data.items[0].daysUntilDue", 2,
				"$.data.items[1].daysUntilDue", 0,
				"$.data.items[2].daysUntilDue", -2,
				"$.data.items[3].daysUntilDue", null);
		// 조회 날짜가 아니라 오늘 기준이다.
		long other = GoalTestSupport.seedAction(jdbc, 1L, null, "다른 날", LocalDate.of(2026, 11, 20), false, false);
		jdbc.update("UPDATE actions SET due_date = DATE '2026-11-13' WHERE id = ?", other);
		api.expect(api.get("/api/actions?date=2026-11-20", 1L), 200, "$.data.items[0].daysUntilDue", 1);
	}

	@Test
	void hiddenActionsAreExcluded() throws Exception {
		long visible = api.goalAction(1L, w1, "보임", "");
		long deletedSelf = GoalTestSupport.seedAction(jdbc, 1L, w1, "삭제됨", TODAY, false, true);
		long deletedCompleted = GoalTestSupport.seedAction(jdbc, 1L, w1, "완료 후 삭제", TODAY, true, true);
		GoalTestSupport.seedAction(jdbc, 2L, null, "남의 것", TODAY, false, false);
		api.expect(api.get("/api/actions", 1L), 200, "$.data.items.length()", 1, "$.data.items[0].id", (int) visible);
		assertThat(deletedSelf).isNotEqualTo(deletedCompleted);

		// 상위 목표가 삭제되면 미완료 행동은 목록(오늘·지난 미완료)에서 빠진다. 완료한 행동은 완료 기록이라 남는다.
		long done = GoalTestSupport.seedAction(jdbc, 1L, w1, "완료한 것", TODAY, true, false);
		GoalTestSupport.seedAction(jdbc, 1L, w1, "지난 미완료", LocalDate.of(2026, 11, 9), false, false);
		jdbc.update("UPDATE monthly_goals SET deleted_at = CURRENT_TIMESTAMP WHERE id = ?", monthly);
		api.expect(api.get("/api/actions", 1L), 200,
				"$.data.items.length()", 1, "$.data.items[0].id", (int) done,
				"$.data.items[0].isCompleted", true,
				"$.data.summary.goal.total", 1, "$.data.summary.goal.completed", 1,
				"$.data.pastIncomplete.count", 0);
	}

	@Test
	void pathStatusAndDisplayColorFollowTheWeeklyPath() throws Exception {
		api.expect(api.patch("/api/goals/weekly/" + w1, 1L,
				"{\"color\":\"#112233\",\"version\":" + version("weekly_goals", w1) + "}"), 200);
		jdbc.update("UPDATE monthly_goals SET status = 'ON_HOLD' WHERE id = ?", monthly);
		api.goalAction(1L, w1, "경로 표시", "");
		api.goalAction(1L, n1, "색 상속", "");
		MvcResult result = api.get("/api/actions", 1L);
		api.expect(result, 200,
				"$.data.items[0].displayColor", "#112233",
				"$.data.items[0].pathStatus", "ON_HOLD",
				// 주간 색이 없으면 월간 -> 연간 순으로 상속한다.
				"$.data.items[1].displayColor", "#3B82F6",
				"$.data.items[1].pathStatus", "ON_HOLD");
	}

	private long version(String table, long id) {
		return jdbc.queryForObject("SELECT version FROM " + table + " WHERE id = ?", Long.class, id);
	}
}
