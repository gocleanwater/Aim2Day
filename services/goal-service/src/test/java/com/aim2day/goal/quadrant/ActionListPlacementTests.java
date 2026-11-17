package com.aim2day.goal.quadrant;

import static org.assertj.core.api.Assertions.assertThat;

import com.aim2day.goal.support.GoalTestSupport;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class ActionListPlacementTests extends QuadrantTestBase {

	/** 좌표: a(50,50)=100, b(90,20)=110, c(60,50)=110(x 더 작음), d(70,70)=140, e 미분류. 각각 id 순서대로 만든다. */
	private long[] seed() throws Exception {
		long a = api.goalAction(1L, w1, "a", "");
		long b = api.maintenance(1L, "b", "");
		long c = api.goalAction(1L, w1, "c", "");
		long d = api.maintenance(1L, "d", "");
		long e = api.maintenance(1L, "e", "");
		put(a, 50, 50);
		put(b, 90, 20);
		put(c, 60, 50);
		put(d, 70, 70);
		return new long[] {a, b, c, d, e};
	}

	private void expectOrder(String sort, long... ids) throws Exception {
		Object[] expectations = new Object[ids.length * 2];
		for (int i = 0; i < ids.length; i++) {
			expectations[i * 2] = "$.data.items[" + i + "].id";
			expectations[i * 2 + 1] = (int) ids[i];
		}
		api.expect(api.get("/api/actions?sort=" + sort, 1L), 200, expectations);
	}

	@Test
	void sortsByDisplayedCoordinatesWithUnclassifiedLast() throws Exception {
		long[] id = seed();
		long a = id[0], b = id[1], c = id[2], d = id[3], e = id[4];
		// x+y 내림차순 -> x 내림차순 -> id. d(140), b(110, x=90), c(110, x=60), a(100), 미분류 e.
		expectOrder("IMPORTANCE_URGENCY", d, b, c, a, e);
		// x 내림차순: b(90), d(70), c(60), a(50), e.
		expectOrder("IMPORTANCE", b, d, c, a, e);
		// y 내림차순: d(70), a(50)·c(50)은 id 순, b(20), e.
		expectOrder("URGENCY", d, a, c, b, e);
		// 기본 정렬은 IMPORTANCE_URGENCY다.
		api.expect(api.get("/api/actions", 1L), 200, "$.data.items[0].id", (int) d);
		// GROUP: GOAL(a, c) 먼저, 그다음 MAINTENANCE(b, d, e).
		expectOrder("GROUP", a, c, b, d, e);
	}

	@Test
	void sortUsesPendingPositionAsDisplayed() throws Exception {
		long[] id = seed();
		confirm();
		// a를 (95, 95)로 옮기면 확정 전이라도 화면의 점(미확정 위치)으로 정렬된다.
		put(id[0], 95, 95);
		api.expect(api.get("/api/actions?sort=IMPORTANCE_URGENCY", 1L), 200,
				"$.data.items[0].id", (int) id[0], "$.data.items[0].placement.state", "RECLASSIFYING");
		// d를 트레이로 되돌리면(되돌리기 대기) 점이 없는 것으로 보고 미분류와 함께 뒤로 간다.
		putTray(id[3]);
		api.expect(api.get("/api/actions?sort=IMPORTANCE_URGENCY", 1L), 200,
				"$.data.items[0].id", (int) id[0], "$.data.items[1].id", (int) id[1], "$.data.items[2].id", (int) id[2],
				"$.data.items[3].id", (int) id[3], "$.data.items[4].id", (int) id[4],
				"$.data.items[3].placement.state", "PENDING_UNCLASSIFY");
	}

	@Test
	void classificationCountsFromItems() throws Exception {
		long[] id = seed();
		// 미확정 4개, 미분류 1개.
		api.expect(api.get("/api/actions", 1L), 200,
				"$.data.classification.unclassified", 1, "$.data.classification.unconfirmedChanges", 4,
				"$.data.classification.confirmed", 0, "$.data.classification.confirmedToday", false);
		confirm();
		put(id[0], 60, 60);
		putTray(id[1]);
		// a RECLASSIFYING, b PENDING_UNCLASSIFY, c·d CONFIRMED, e UNCLASSIFIED.
		// 세 수는 겹치지 않는다. 되돌리기 대기(b)는 트레이에 있어도 unclassified가 아니라 unconfirmedChanges로 센다.
		api.expect(api.get("/api/actions", 1L), 200,
				"$.data.classification.unclassified", 1, "$.data.classification.unconfirmedChanges", 2,
				"$.data.classification.confirmed", 2, "$.data.classification.confirmedToday", true);
	}

	@Test
	void pastDatePendingIsIgnoredAndPastIncompleteUsesConfirmedOnly() throws Exception {
		LocalDate yesterday = LocalDate.of(2026, 11, 11);
		long past = GoalTestSupport.seedAction(jdbc, 1L, null, "어제 미완료", yesterday, false, false);
		long pastConfirmed = GoalTestSupport.seedAction(jdbc, 1L, null, "어제 확정", yesterday, false, false);
		jdbc.update("INSERT INTO action_quadrant_placements (action_id, classification_date, pending_importance_x, "
				+ "pending_urgency_y) VALUES (?, ?, 80, 80)", past, java.sql.Date.valueOf(yesterday));
		jdbc.update("INSERT INTO action_quadrant_placements (action_id, classification_date, importance_x, urgency_y, "
				+ "pending_importance_x, pending_urgency_y) VALUES (?, ?, 20, 30, 90, 90)", pastConfirmed,
				java.sql.Date.valueOf(yesterday));

		// 어제 날짜 조회: 미확정은 없는 것으로 본다.
		api.expect(api.get("/api/actions?date=2026-11-11&sort=GROUP", 1L), 200,
				"$.data.items[0].placement.state", "UNCLASSIFIED", "$.data.items[0].placement.pending", null,
				"$.data.items[1].placement.state", "CONFIRMED", "$.data.items[1].placement.displayed.importance", 20,
				"$.data.items[1].placement.pending", null,
				"$.data.classification.unconfirmedChanges", 0, "$.data.classification.unclassified", 1,
				"$.data.classification.confirmed", 1);
		// 오늘 목록의 pastIncomplete 항목도 같은 규칙이고, classification 수에는 들어가지 않는다.
		api.expect(api.get("/api/actions", 1L), 200,
				"$.data.items.length()", 0,
				"$.data.classification.unclassified", 0, "$.data.classification.unconfirmedChanges", 0,
				"$.data.pastIncomplete.count", 2,
				"$.data.pastIncomplete.items[0].placement.state", "UNCLASSIFIED",
				"$.data.pastIncomplete.items[1].placement.state", "CONFIRMED");
		assertThat(past).isNotEqualTo(pastConfirmed);
	}

	@Test
	void weeklyGoalDetailActionsCarryPlacement() throws Exception {
		long a = api.goalAction(1L, w1, "a", "");
		put(a, 70, 40);
		api.expect(api.get("/api/goals/weekly/" + w1, 1L), 200,
				"$.data.actions[0].placement.state", "PENDING", "$.data.actions[0].placement.displayed.importance", 70);
	}
}
