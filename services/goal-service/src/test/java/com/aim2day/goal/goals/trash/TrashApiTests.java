package com.aim2day.goal.goals.trash;

import org.junit.jupiter.api.Test;

class TrashApiTests extends TrashTestBase {

	@Test
	void emptyTrashIsEmptyList() throws Exception {
		api.expect(api.get("/api/goals/trash", 1L), 200, "$.data.length()", 0);
	}

	@Test
	void listShapeForEachLevel() throws Exception {
		api.expect(delete(1L, "weekly", w1, false), 200);
		api.expect(api.get("/api/goals/trash", 1L), 200,
				"$.data.length()", 1,
				"$.data[0].level", "WEEKLY", "$.data[0].id", (int) w1, "$.data[0].title", "체크 주간",
				"$.data[0].periodLabel", "2026-11-08~2026-11-14",
				"$.data[0].deletedAt", "2026-11-12T03:00:00Z", "$.data[0].restoreDeadline", "2026-12-12T03:00:00Z",
				"$.data[0].remainingDays", 30,
				"$.data[0].parent.level", "MONTHLY", "$.data[0].parent.id", (int) monthly,
				"$.data[0].parent.title", "월간", "$.data[0].isParentDeleted", false);

		// 같은 시각에 삭제했으므로 단계 순(연간 -> 월간 -> 주간)으로 정렬된다.
		api.expect(delete(1L, "monthly", monthly, true), 200);
		api.expect(api.get("/api/goals/trash", 1L), 200,
				"$.data[0].level", "MONTHLY", "$.data[0].periodLabel", "2026-11",
				"$.data[0].parent.level", "YEARLY", "$.data[0].parent.id", (int) yearly,
				"$.data[0].parent.title", "연간", "$.data[0].isParentDeleted", false);

		api.expect(delete(1L, "yearly", yearly, true), 200);
		api.expect(api.get("/api/goals/trash", 1L), 200,
				"$.data[0].level", "YEARLY", "$.data[0].periodLabel", "2026", "$.data[0].title", "연간",
				"$.data[0].isParentDeleted", false,
				// 월간의 부모(연간)가 이제 삭제 상태다.
				"$.data[1].level", "MONTHLY", "$.data[1].isParentDeleted", true);
	}

	@Test
	void yearlyHasNullParent() throws Exception {
		api.expect(delete(1L, "yearly", yearly, true), 200);
		api.expect(api.get("/api/goals/trash", 1L), 200, "$.data.length()", 1, "$.data[0].level", "YEARLY",
				"$.data[0].parent", null);
	}

	@Test
	void onlyDirectlyDeletedGoalsAreListedAndParentDeletedIsFlagged() throws Exception {
		// w1을 먼저 따로 삭제하고, 이어 월간을 삭제한다. n1은 월간 삭제로 숨겨졌을 뿐이라 목록에 나오지 않는다.
		api.expect(delete(1L, "weekly", w1, false), 200);
		clockAt("2026-11-12T04:00:00Z");
		api.expect(delete(1L, "monthly", monthly, true), 200);

		api.expect(api.get("/api/goals/trash", 1L), 200,
				"$.data.length()", 2,
				// 최근 삭제가 먼저다.
				"$.data[0].level", "MONTHLY", "$.data[0].isParentDeleted", false,
				"$.data[1].level", "WEEKLY", "$.data[1].id", (int) w1, "$.data[1].isParentDeleted", true);
	}

	@Test
	void grandparentDeletionAlsoFlagsParentDeleted() throws Exception {
		api.expect(delete(1L, "weekly", w1, false), 200);
		clockAt("2026-11-12T04:00:00Z");
		api.expect(delete(1L, "yearly", yearly, true), 200);
		// w1의 부모 월간은 삭제되지 않았지만 그 위 연간이 삭제됐다.
		api.expect(api.get("/api/goals/trash", 1L), 200,
				"$.data[1].level", "WEEKLY", "$.data[1].isParentDeleted", true);
	}

	@Test
	void sortsByRecentThenLevelThenId() throws Exception {
		long y2 = api.yearly(1L, "연간2");
		long m2 = api.monthly(1L, y2, "월간2");
		long w2 = api.weekly(1L, m2, "주간2");
		long y3 = api.yearly(1L, "연간3");
		// 같은 삭제 시각의 세 단계 + 더 최근 하나.
		String same = "2026-11-10T00:00:00Z";
		setDeletedAt("weekly_goals", w1, same);
		setDeletedAt("monthly_goals", m2, same);
		setDeletedAt("yearly_goals", y3, same);
		setDeletedAt("weekly_goals", w2, "2026-11-11T00:00:00Z");
		setDeletedAt("weekly_goals", n1, same);

		api.expect(api.get("/api/goals/trash", 1L), 200,
				"$.data.length()", 5,
				"$.data[0].id", (int) w2,
				"$.data[1].level", "YEARLY", "$.data[1].id", (int) y3,
				"$.data[2].level", "MONTHLY", "$.data[2].id", (int) m2,
				"$.data[3].level", "WEEKLY", "$.data[3].id", (int) Math.min(w1, n1),
				"$.data[4].level", "WEEKLY", "$.data[4].id", (int) Math.max(w1, n1));
	}

	@Test
	void expiredItemsAreExcludedAndDeadlineDayShowsZeroRemaining() throws Exception {
		setDeletedAt("weekly_goals", w1, "2026-10-13T03:00:00Z"); // 정확히 30일 전: 아직 복원 가능
		setDeletedAt("weekly_goals", n1, "2026-10-13T02:59:59Z"); // 1초 지남: 기한 만료
		api.expect(api.get("/api/goals/trash", 1L), 200,
				"$.data.length()", 1, "$.data[0].id", (int) w1, "$.data[0].remainingDays", 0,
				"$.data[0].restoreDeadline", "2026-11-12T03:00:00Z");
	}

	@Test
	void remainingDaysFollowsAccountTimezoneDate() throws Exception {
		api.expect(delete(1L, "weekly", w1, false), 200);
		clockAt("2026-11-22T03:00:00Z");
		api.expect(api.get("/api/goals/trash", 1L), 200, "$.data[0].remainingDays", 20);
		// 기한(12-12T03:00Z)의 서울 날짜는 12-12다. 서울 11-22 23:30에는 날짜 차가 20이다(올림 없음).
		clockAt("2026-11-22T14:30:00Z");
		api.expect(api.get("/api/goals/trash", 1L), 200, "$.data[0].remainingDays", 20);
	}

	@Test
	void otherUsersTrashIsNotVisible() throws Exception {
		api.expect(delete(1L, "weekly", w1, false), 200);
		api.expect(api.get("/api/goals/trash", 2L), 200, "$.data.length()", 0);
	}

	@Test
	void remainingDaysAroundAccountMidnightOfDeadlineDate() throws Exception {
		api.expect(api.put("/api/settings", 1L,
				"{\"weekStartDay\":\"MONDAY\",\"timezone\":\"Asia/Seoul\",\"version\":0}"), 200);
		api.expect(delete(1L, "weekly", w1, false), 200); // 기한 2026-12-12T03:00Z = 서울 12-12 12:00
		clockAt("2026-12-11T14:59:00Z"); // 서울 12-11 23:59, 기한 날짜 전날
		api.expect(api.get("/api/goals/trash", 1L), 200, "$.data[0].remainingDays", 1);
		clockAt("2026-12-11T15:00:00Z"); // 서울 12-12 00:00, 기한 날짜 당일 오전
		api.expect(api.get("/api/goals/trash", 1L), 200, "$.data[0].remainingDays", 0);
	}
}
