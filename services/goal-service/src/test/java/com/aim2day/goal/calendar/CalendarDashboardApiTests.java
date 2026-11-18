package com.aim2day.goal.calendar;

import static org.assertj.core.api.Assertions.assertThat;

import com.aim2day.goal.support.GoalTestSupport;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 대시보드(GET /api/calendar/dashboard). 시계 2026-11-12T03:00Z, 계정 시간대 서울이라 오늘은 11-12다.
 * 오늘+7 = 11-19, 오늘-7 = 11-05.
 */
class CalendarDashboardApiTests extends CalendarTestBase {

	private MvcResult dashboard() throws Exception {
		return api.get("/api/calendar/dashboard", 1L);
	}

	private void complete(long id) throws Exception {
		api.expect(api.put(completionPath(id), 1L, "{\"completed\":true}"), 200);
	}

	@Test
	void emptyDashboardHasTodayAndThreeEmptyLists() throws Exception {
		api.expect(dashboard(), 200,
				"$.data.today", "2026-11-12",
				"$.data.tasks.length()", 0,
				"$.data.events.length()", 0,
				"$.data.pastEvents.length()", 0);
	}

	@Test
	void todayFollowsTheAccountTimezone() throws Exception {
		// UTC 11-12T15:30Z는 서울 기준 11-13이다. 서울 기준 11-13이 "오늘"이면 11-12에 끝난 일정은 지난 일정(D+1)이다.
		long ended = event("어제 끝남", "2026-11-12", null, "");
		clock.set(Instant.parse("2026-11-12T15:30:00Z"));
		api.expect(dashboard(), 200,
				"$.data.today", "2026-11-13",
				"$.data.events.length()", 0,
				"$.data.pastEvents[0].id", (int) ended,
				"$.data.pastEvents[0].daysSinceEnd", 1);
	}

	// ------------------------------------------------------------ 해야 할 일

	@Test
	void tasksAreAllOpenTasksRegardlessOfDueDateOrderedByDueDateThenId() throws Exception {
		long far = task("먼 마감", "2027-03-01", "");
		long overdue = task("지난 마감", "2026-09-01", "");
		long todayB = task("오늘 B", "2026-11-12", "");
		long todayA = task("오늘 A", "2026-11-12", "");
		long done = task("완료됨", "2026-11-13", "");
		complete(done);
		event("일정은 제외", "2026-11-13", null, "");

		api.expect(dashboard(), 200,
				"$.data.tasks.length()", 4,
				"$.data.tasks[0].id", (int) overdue,
				"$.data.tasks[1].id", (int) todayB,
				"$.data.tasks[2].id", (int) todayA,
				"$.data.tasks[3].id", (int) far,
				"$.data.tasks[0].daysUntilDue", -72,
				"$.data.tasks[1].daysUntilDue", 0);
		assertThat(todayB).isLessThan(todayA);
	}

	@Test
	void completedTaskLeavesTheListAndComesBackWhenReopened() throws Exception {
		long id = task("과제", "2026-11-14", "");
		api.expect(dashboard(), 200, "$.data.tasks.length()", 1);
		complete(id);
		api.expect(dashboard(), 200, "$.data.tasks.length()", 0);
		api.expect(api.put(completionPath(id), 1L, "{\"completed\":false}"), 200);
		api.expect(dashboard(), 200, "$.data.tasks.length()", 1);
	}

	// ------------------------------------------------------------ 일정 (Review Focus 4)

	@Test
	void eventsStartingWithinSevenDaysOrInProgressAreIncluded() throws Exception {
		long startsPlus7 = event("+7 시작", "2026-11-19", null, "");
		long startsPlus8 = event("+8 시작", "2026-11-20", null, "");
		long today = event("오늘", "2026-11-12", null, "");
		long inProgress = event("진행 중", "2026-11-01", "2026-11-12", "");
		long longRunning = event("오래 진행", "2026-10-01", "2026-12-31", "");
		long endedYesterday = event("어제 끝남", "2026-11-10", "2026-11-11", "");
		long soon = event("내일", "2026-11-13", null, "");

		MvcResult result = dashboard();
		api.expect(result, 200,
				"$.data.events.length()", 5,
				// 시작일 순, 같으면 id 순
				"$.data.events[0].id", (int) longRunning,
				"$.data.events[1].id", (int) inProgress,
				"$.data.events[2].id", (int) today,
				"$.data.events[3].id", (int) soon,
				"$.data.events[4].id", (int) startsPlus7,
				// EVENT의 daysUntilDue는 시작일까지다(진행 중이면 음수).
				"$.data.events[0].daysUntilDue", -42,
				"$.data.events[2].daysUntilDue", 0,
				"$.data.events[4].daysUntilDue", 7);
		// +8일 시작은 어느 목록에도 없고, 어제 끝난 일정은 events가 아니라 pastEvents에 있다.
		assertThat(api.body(result)).doesNotContain("\"id\":" + startsPlus8 + ",");
		api.expect(result, 200, "$.data.pastEvents.length()", 1, "$.data.pastEvents[0].id", (int) endedYesterday);
	}

	@Test
	void completedEventsStayInEventsList() throws Exception {
		long id = event("끝낸 일정", "2026-11-13", null, "");
		complete(id);
		api.expect(dashboard(), 200, "$.data.events.length()", 1, "$.data.events[0].isCompleted", true);
	}

	@Test
	void tasksNeverAppearInEventListsEvenWithinTheWindow() throws Exception {
		task("해야 할 일", "2026-11-13", "");
		api.expect(dashboard(), 200, "$.data.events.length()", 0, "$.data.pastEvents.length()", 0,
				"$.data.tasks.length()", 1);
	}

	// ------------------------------------------------------------ 지난 일정 (Review Focus 4)

	@Test
	void pastEventsAreThoseEndedWithinTheLastSevenDays() throws Exception {
		long yesterday = event("어제 끝남", "2026-11-10", "2026-11-11", "");
		long sevenDaysAgo = event("7일 전 끝남", "2026-11-01", "2026-11-05", "");
		long eightDaysAgo = event("8일 전 끝남", "2026-11-01", "2026-11-04", "");
		long endsToday = event("오늘 끝남", "2026-11-10", "2026-11-12", "");
		long donePast = event("완료한 지난 일정", "2026-11-08", null, "");
		complete(donePast);
		task("지난 TASK는 제외", "2026-11-10", "");

		MvcResult result = dashboard();
		api.expect(result, 200,
				"$.data.pastEvents.length()", 3,
				// 종료일 순(오래된 것 먼저), 같으면 id 순
				"$.data.pastEvents[0].id", (int) sevenDaysAgo,
				"$.data.pastEvents[0].daysSinceEnd", 7,
				"$.data.pastEvents[1].id", (int) donePast,
				"$.data.pastEvents[1].daysSinceEnd", 4,
				"$.data.pastEvents[1].isCompleted", true,
				"$.data.pastEvents[2].id", (int) yesterday,
				"$.data.pastEvents[2].daysSinceEnd", 1);
		// 오늘 끝나는 일정은 아직 진행 중이므로 events에 있고 pastEvents에는 없다.
		api.expect(result, 200, "$.data.events.length()", 1, "$.data.events[0].id", (int) endsToday);
		assertThat(api.body(result)).doesNotContain("\"id\":" + eightDaysAgo + ",");
	}

	@Test
	void pastEventKeepsTheCalendarItemFields() throws Exception {
		long id = event("지난 발표", "2026-11-11", null, ",\"yearlyGoalId\":" + y1);
		api.expect(dashboard(), 200,
				"$.data.pastEvents[0].id", (int) id,
				"$.data.pastEvents[0].itemType", "EVENT",
				"$.data.pastEvents[0].title", "지난 발표",
				"$.data.pastEvents[0].startDate", "2026-11-11",
				"$.data.pastEvents[0].endDate", "2026-11-11",
				"$.data.pastEvents[0].yearlyGoal.id", (int) y1,
				"$.data.pastEvents[0].yearlyGoal.color", "#3B82F6",
				"$.data.pastEvents[0].daysUntilDue", -1,
				"$.data.pastEvents[0].version", 0,
				"$.data.pastEvents[0].daysSinceEnd", 1);
	}

	// ------------------------------------------------------------ 소유권·삭제된 목표

	@Test
	void dashboardShowsOnlyTheCallersItems() throws Exception {
		task("내 할 일", "2026-11-13", "");
		item(2L, "{\"itemType\":\"TASK\",\"title\":\"남의 할 일\",\"startDate\":\"2026-11-13\"}");
		item(2L, "{\"itemType\":\"EVENT\",\"title\":\"남의 일정\",\"startDate\":\"2026-11-13\"}");
		api.expect(dashboard(), 200, "$.data.tasks.length()", 1, "$.data.tasks[0].title", "내 할 일",
				"$.data.events.length()", 0);
	}

	@Test
	void itemLinkedToDeletedGoalShowsNullGoalOnDashboard() throws Exception {
		long id = task("과제", "2026-11-13", ",\"yearlyGoalId\":" + y1);
		api.expect(dashboard(), 200, "$.data.tasks[0].yearlyGoal.id", (int) y1);
		api.expect(api.delete("/api/goals/yearly/" + y1 + "?confirm=true", 1L), 200);
		MvcResult result = dashboard();
		api.expect(result, 200, "$.data.tasks.length()", 1, "$.data.tasks[0].id", (int) id,
				"$.data.tasks[0].yearlyGoal", null);
		assertThat((Object) GoalTestSupport.read(result, "$.data.tasks[0].yearlyGoal")).isNull();
	}
}
