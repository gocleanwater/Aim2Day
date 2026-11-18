package com.aim2day.goal.calendar;

import static org.assertj.core.api.Assertions.assertThat;

import com.aim2day.goal.support.GoalTestSupport;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

/** 달력 항목 생성·수정·완료·삭제(API 명세 §4.11). 오늘은 2026-11-12(서울)다. */
class CalendarItemApiTests extends CalendarTestBase {

	private static final String ITEMS = "/api/calendar/items";

	// ------------------------------------------------------------ 생성

	@Test
	void createsTaskWithDueDateAndGoal() throws Exception {
		MvcResult result = api.post(ITEMS, 1L, "{\"itemType\":\"TASK\",\"title\":\"  과제 제출  \",\"description\":null,"
				+ "\"startDate\":\"2026-11-14\",\"endDate\":null,\"yearlyGoalId\":" + y1 + "}");
		api.expect(result, 201,
				"$.code", "SUCCESS",
				"$.data.itemType", "TASK",
				// 앞뒤 공백은 지운다.
				"$.data.title", "과제 제출",
				"$.data.description", null,
				"$.data.startDate", "2026-11-14",
				// TASK는 저장이 endDate = startDate다.
				"$.data.endDate", "2026-11-14",
				"$.data.isCompleted", false,
				"$.data.completedAt", null,
				"$.data.yearlyGoal.id", (int) y1,
				"$.data.yearlyGoal.title", "백엔드 개발자 취업 준비",
				"$.data.yearlyGoal.color", "#3B82F6",
				"$.data.daysUntilDue", 2,
				"$.data.version", 0);
	}

	@Test
	void taskEndDateMustBeOmittedOrEqualToStart() throws Exception {
		api.expect(api.post(ITEMS, 1L, "{\"itemType\":\"TASK\",\"title\":\"a\",\"startDate\":\"2026-11-14\","
				+ "\"endDate\":\"2026-11-14\"}"), 201, "$.data.endDate", "2026-11-14");
		MvcResult bad = api.post(ITEMS, 1L, "{\"itemType\":\"TASK\",\"title\":\"a\",\"startDate\":\"2026-11-14\","
				+ "\"endDate\":\"2026-11-15\"}");
		api.expect(bad, 400, "$.code", "VALIDATION_FAILED",
				"$.details.fieldErrors[0].field", "endDate", "$.details.fieldErrors[0].reason", "INVALID_FORMAT");
	}

	@Test
	void eventDefaultsToOneDayAndAcceptsARange() throws Exception {
		api.expect(api.post(ITEMS, 1L, "{\"itemType\":\"EVENT\",\"title\":\"발표\",\"startDate\":\"2026-11-13\"}"), 201,
				"$.data.endDate", "2026-11-13", "$.data.daysUntilDue", 1);
		api.expect(api.post(ITEMS, 1L, "{\"itemType\":\"EVENT\",\"title\":\"캠프\",\"startDate\":\"2026-11-13\","
				+ "\"endDate\":\"2026-11-15\"}"), 201, "$.data.endDate", "2026-11-15");
		MvcResult bad = api.post(ITEMS, 1L, "{\"itemType\":\"EVENT\",\"title\":\"캠프\",\"startDate\":\"2026-11-13\","
				+ "\"endDate\":\"2026-11-12\"}");
		api.expect(bad, 400, "$.details.fieldErrors[0].field", "endDate",
				"$.details.fieldErrors[0].reason", "OUT_OF_RANGE");
	}

	@Test
	void pastDueTaskHasNegativeDaysUntilDue() throws Exception {
		api.expect(api.post(ITEMS, 1L, "{\"itemType\":\"TASK\",\"title\":\"밀림\",\"startDate\":\"2026-11-10\"}"), 201,
				"$.data.daysUntilDue", -2);
	}

	@Test
	void validatesRequiredFieldsAndLengths() throws Exception {
		api.expect(api.post(ITEMS, 1L, "{\"title\":\"a\",\"startDate\":\"2026-11-14\"}"), 400,
				"$.details.fieldErrors[0].field", "itemType", "$.details.fieldErrors[0].reason", "REQUIRED");
		api.expect(api.post(ITEMS, 1L, "{\"itemType\":\"TASK\",\"title\":\"   \",\"startDate\":\"2026-11-14\"}"), 400,
				"$.details.fieldErrors[0].field", "title", "$.details.fieldErrors[0].reason", "REQUIRED");
		api.expect(api.post(ITEMS, 1L, "{\"itemType\":\"TASK\",\"title\":\"" + "가".repeat(256)
				+ "\",\"startDate\":\"2026-11-14\"}"), 400,
				"$.details.fieldErrors[0].field", "title", "$.details.fieldErrors[0].reason", "TOO_LONG");
		api.expect(api.post(ITEMS, 1L, "{\"itemType\":\"TASK\",\"title\":\"" + "가".repeat(255)
				+ "\",\"startDate\":\"2026-11-14\"}"), 201);
		api.expect(api.post(ITEMS, 1L, "{\"itemType\":\"TASK\",\"title\":\"a\",\"description\":\"" + "x".repeat(2001)
				+ "\",\"startDate\":\"2026-11-14\"}"), 400,
				"$.details.fieldErrors[0].field", "description", "$.details.fieldErrors[0].reason", "TOO_LONG");
		api.expect(api.post(ITEMS, 1L, "{\"itemType\":\"TASK\",\"title\":\"a\"}"), 400,
				"$.details.fieldErrors[0].field", "startDate", "$.details.fieldErrors[0].reason", "REQUIRED");
		api.expect(api.post(ITEMS, 1L, "{\"itemType\":\"MEETING\",\"title\":\"a\",\"startDate\":\"2026-11-14\"}"), 400,
				"$.code", "MALFORMED_REQUEST");
		assertThat(count("SELECT COUNT(*) FROM calendar_items")).isEqualTo(1);
	}

	@Test
	void linkedGoalMustBeOwnedAndVisible() throws Exception {
		long others = api.yearly(2L, "남의 목표");
		long deleted = api.yearly(1L, "삭제할 목표");
		api.expect(api.delete("/api/goals/yearly/" + deleted + "?confirm=true", 1L), 200);

		for (long goalId : new long[] {others, deleted, 999999L}) {
			api.expect(api.post(ITEMS, 1L, "{\"itemType\":\"TASK\",\"title\":\"a\",\"startDate\":\"2026-11-14\","
					+ "\"yearlyGoalId\":" + goalId + "}"), 422,
					"$.code", "GOAL_PARENT_UNAVAILABLE",
					"$.details.parentLevel", "YEARLY", "$.details.parentId", (int) goalId);
		}
		assertThat(count("SELECT COUNT(*) FROM calendar_items")).isZero();
	}

	@Test
	void goalStatusIsNotChecked() throws Exception {
		// "활성"을 삭제되지 않음으로 읽는다(가정). 보류 상태의 목표에도 연결할 수 있다.
		api.expect(api.patch("/api/goals/yearly/" + y1, 1L, "{\"status\":\"ON_HOLD\",\"version\":0}"), 200);
		api.expect(api.post(ITEMS, 1L, "{\"itemType\":\"TASK\",\"title\":\"a\",\"startDate\":\"2026-11-14\","
				+ "\"yearlyGoalId\":" + y1 + "}"), 201, "$.data.yearlyGoal.id", (int) y1);
	}

	// ------------------------------------------------------------ 수정

	@Test
	void patchChangesFieldsAndBumpsVersion() throws Exception {
		long id = event("발표", "2026-11-13", "2026-11-14", "");
		MvcResult result = api.patch(ITEMS + "/" + id, 1L, "{\"title\":\"최종 발표\",\"description\":\"자료 준비\","
				+ "\"yearlyGoalId\":" + y1 + ",\"version\":0}");
		api.expect(result, 200,
				"$.data.title", "최종 발표",
				"$.data.description", "자료 준비",
				// 보내지 않은 날짜는 유지한다.
				"$.data.startDate", "2026-11-13",
				"$.data.endDate", "2026-11-14",
				"$.data.yearlyGoal.id", (int) y1,
				"$.data.version", 1);
	}

	@Test
	void patchRequiresCurrentVersion() throws Exception {
		long id = task("과제", "2026-11-14", "");
		api.expect(api.patch(ITEMS + "/" + id, 1L, "{\"title\":\"b\",\"version\":0}"), 200, "$.data.version", 1);
		// 오래된 화면(version 0)은 409이고 details에 현재 version이 담긴다.
		api.expect(api.patch(ITEMS + "/" + id, 1L, "{\"title\":\"c\",\"version\":0}"), 409,
				"$.code", "VERSION_CONFLICT", "$.details.currentVersion", 1);
		api.expect(api.patch(ITEMS + "/" + id, 1L, "{\"title\":\"c\"}"), 400,
				"$.details.fieldErrors[0].field", "version", "$.details.fieldErrors[0].reason", "REQUIRED");
		assertThat(jdbc.queryForObject("SELECT title FROM calendar_items WHERE id = ?", String.class, id)).isEqualTo("b");
	}

	@Test
	void itemTypeCannotChangeButSameTypeIsIgnored() throws Exception {
		long id = task("과제", "2026-11-14", "");
		api.expect(api.patch(ITEMS + "/" + id, 1L, "{\"itemType\":\"EVENT\",\"version\":0}"), 422,
				"$.code", "CALENDAR_ITEM_TYPE_IMMUTABLE");
		api.expect(api.patch(ITEMS + "/" + id, 1L, "{\"itemType\":\"TASK\",\"title\":\"b\",\"version\":0}"), 200,
				"$.data.itemType", "TASK", "$.data.title", "b");
		api.expect(api.patch(ITEMS + "/" + id, 1L, "{\"itemType\":null,\"version\":1}"), 400,
				"$.details.fieldErrors[0].field", "itemType");
	}

	@Test
	void nullYearlyGoalIdUnlinksAndNullTitleIsRejected() throws Exception {
		long id = task("과제", "2026-11-14", ",\"yearlyGoalId\":" + y1);
		api.expect(api.patch(ITEMS + "/" + id, 1L, "{\"title\":null,\"version\":0}"), 400,
				"$.details.fieldErrors[0].field", "title", "$.details.fieldErrors[0].reason", "REQUIRED");
		api.expect(api.patch(ITEMS + "/" + id, 1L, "{\"yearlyGoalId\":null,\"version\":0}"), 200,
				"$.data.yearlyGoal", null, "$.data.version", 1);
		assertThat(jdbc.queryForObject("SELECT yearly_goal_id FROM calendar_items WHERE id = ?", Long.class, id)).isNull();
	}

	@Test
	void patchedGoalMustBeOwnedAndVisible() throws Exception {
		long others = api.yearly(2L, "남의 목표");
		long id = task("과제", "2026-11-14", "");
		api.expect(api.patch(ITEMS + "/" + id, 1L, "{\"yearlyGoalId\":" + others + ",\"version\":0}"), 422,
				"$.code", "GOAL_PARENT_UNAVAILABLE", "$.details.parentId", (int) others);
		assertThat(jdbc.queryForObject("SELECT version FROM calendar_items WHERE id = ?", Long.class, id)).isZero();
	}

	@Test
	void changingTaskStartDateKeepsEndEqualToStart() throws Exception {
		long id = task("과제", "2026-11-14", "");
		api.expect(api.patch(ITEMS + "/" + id, 1L, "{\"startDate\":\"2026-11-20\",\"version\":0}"), 200,
				"$.data.startDate", "2026-11-20", "$.data.endDate", "2026-11-20");
		// 시작일과 다른 종료일은 TASK에서 400이다.
		api.expect(api.patch(ITEMS + "/" + id, 1L, "{\"endDate\":\"2026-11-25\",\"version\":1}"), 400,
				"$.details.fieldErrors[0].field", "endDate");
		api.expect(api.patch(ITEMS + "/" + id, 1L, "{\"startDate\":\"2026-11-21\",\"endDate\":\"2026-11-21\","
				+ "\"version\":1}"), 200, "$.data.endDate", "2026-11-21");
	}

	@Test
	void eventDateRulesOnPatch() throws Exception {
		long id = event("캠프", "2026-11-13", "2026-11-15", "");
		// 종료일 이전으로 끝나는 변경은 400이다.
		api.expect(api.patch(ITEMS + "/" + id, 1L, "{\"startDate\":\"2026-11-16\",\"version\":0}"), 400,
				"$.details.fieldErrors[0].field", "endDate", "$.details.fieldErrors[0].reason", "OUT_OF_RANGE");
		api.expect(api.patch(ITEMS + "/" + id, 1L, "{\"startDate\":\"2026-11-16\",\"endDate\":\"2026-11-18\","
				+ "\"version\":0}"), 200, "$.data.startDate", "2026-11-16", "$.data.endDate", "2026-11-18");
		// endDate를 null로 보내면 하루 일정이 된다(가정).
		api.expect(api.patch(ITEMS + "/" + id, 1L, "{\"endDate\":null,\"version\":1}"), 200,
				"$.data.endDate", "2026-11-16");
	}

	@Test
	void patchKeepsCompletionState() throws Exception {
		long id = task("과제", "2026-11-14", "");
		api.expect(api.put(completionPath(id), 1L, "{\"completed\":true}"), 200);
		// 완료는 version을 올리지 않으므로 열려 있던 수정 화면(version 0)이 409가 되지 않고, 수정이 완료 상태를 되돌리지도 않는다.
		api.expect(api.patch(ITEMS + "/" + id, 1L, "{\"title\":\"b\",\"version\":0}"), 200,
				"$.data.isCompleted", true, "$.data.version", 1);
	}

	// ------------------------------------------------------------ 소유권

	@Test
	void otherUsersItemIsNotFoundEverywhere() throws Exception {
		long id = task("내 과제", "2026-11-14", "");
		api.expect(api.patch(ITEMS + "/" + id, 2L, "{\"title\":\"x\",\"version\":0}"), 404,
				"$.code", "RESOURCE_NOT_FOUND");
		api.expect(api.put(completionPath(id), 2L, "{\"completed\":true}"), 404, "$.code", "RESOURCE_NOT_FOUND");
		api.expect(api.delete(ITEMS + "/" + id, 2L), 404, "$.code", "RESOURCE_NOT_FOUND");
		api.expect(api.get(ITEMS + "?from=2026-11-01&to=2026-11-30", 2L), 200, "$.data.length()", 0);
		api.expect(api.patch(ITEMS + "/9999999", 1L, "{\"title\":\"x\",\"version\":0}"), 404);
		// 내 항목은 그대로다.
		assertThat(jdbc.queryForObject("SELECT title FROM calendar_items WHERE id = ?", String.class, id))
				.isEqualTo("내 과제");
		assertThat(count("SELECT COUNT(*) FROM calendar_items WHERE is_completed = TRUE")).isZero();
	}

	// ------------------------------------------------------------ 완료

	@Test
	void completionSetsAndClearsCompletedAtWithoutTouchingVersion() throws Exception {
		long id = task("과제", "2026-11-14", "");
		MvcResult done = api.put(completionPath(id), 1L, "{\"completed\":true}");
		api.expect(done, 200, "$.data.id", (int) id, "$.data.isCompleted", true, "$.data.version", 0);
		assertThat((String) GoalTestSupport.read(done, "$.data.completedAt")).isEqualTo("2026-11-12T03:00:00Z");

		api.expect(api.put(completionPath(id), 1L, "{\"completed\":false}"), 200,
				"$.data.isCompleted", false, "$.data.completedAt", null, "$.data.version", 0);
		assertThat(jdbc.queryForObject("SELECT completed_at FROM calendar_items WHERE id = ?", java.sql.Timestamp.class, id))
				.isNull();
	}

	@Test
	void repeatingTheSameCompletionKeepsCompletedAt() throws Exception {
		long id = task("과제", "2026-11-14", "");
		MvcResult first = api.put(completionPath(id), 1L, "{\"completed\":true}");
		api.expect(first, 200);
		String firstAt = GoalTestSupport.read(first, "$.data.completedAt");

		// 시각이 지난 뒤 같은 상태를 다시 보내도 200이고 완료 시각은 그대로다.
		clock.set(Instant.parse("2026-11-12T09:30:45Z"));
		MvcResult again = api.put(completionPath(id), 1L, "{\"completed\":true}");
		api.expect(again, 200, "$.data.isCompleted", true);
		assertThat((String) GoalTestSupport.read(again, "$.data.completedAt")).isEqualTo(firstAt);

		// 취소를 반복해도 200이다.
		api.expect(api.put(completionPath(id), 1L, "{\"completed\":false}"), 200, "$.data.isCompleted", false);
		api.expect(api.put(completionPath(id), 1L, "{\"completed\":false}"), 200, "$.data.isCompleted", false,
				"$.data.completedAt", null);

		// 다시 완료하면 새 시각이 찍히고, 초 단위로 잘린다.
		clock.set(Instant.parse("2026-11-12T10:00:00.789Z"));
		MvcResult redo = api.put(completionPath(id), 1L, "{\"completed\":true}");
		assertThat((String) GoalTestSupport.read(redo, "$.data.completedAt")).isEqualTo("2026-11-12T10:00:00Z");
	}

	@Test
	void completionRequiresCompletedFlag() throws Exception {
		long id = task("과제", "2026-11-14", "");
		api.expect(api.put(completionPath(id), 1L, "{}"), 400,
				"$.details.fieldErrors[0].field", "completed", "$.details.fieldErrors[0].reason", "REQUIRED");
	}

	@Test
	void completionDoesNotCreateActionsOrProgress() throws Exception {
		long id = task("과제", "2026-11-14", ",\"yearlyGoalId\":" + y1);
		int logs = count("SELECT COUNT(*) FROM goal_progress_logs");
		api.expect(api.put(completionPath(id), 1L, "{\"completed\":true}"), 200);
		assertThat(count("SELECT COUNT(*) FROM actions")).isZero();
		assertThat(count("SELECT COUNT(*) FROM weekly_goal_numeric_records")).isZero();
		assertThat(count("SELECT COUNT(*) FROM goal_progress_logs")).isEqualTo(logs);
	}

	// ------------------------------------------------------------ 삭제

	@Test
	void deleteRemovesTheRowImmediately() throws Exception {
		long id = task("과제", "2026-11-14", "");
		api.expect(api.delete(ITEMS + "/" + id, 1L), 200, "$.code", "SUCCESS", "$.data", null);
		assertThat(count("SELECT COUNT(*) FROM calendar_items WHERE id = ?", id)).isZero();
		api.expect(api.delete(ITEMS + "/" + id, 1L), 404, "$.code", "RESOURCE_NOT_FOUND");
	}
}
