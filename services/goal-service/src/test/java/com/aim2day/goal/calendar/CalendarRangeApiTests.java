package com.aim2day.goal.calendar;

import static org.assertj.core.api.Assertions.assertThat;

import com.aim2day.goal.goals.trash.TrashPurgeService;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

/** 달력 기간 조회(GET /api/calendar/items): 기간 경계, 목표 필터, 삭제된 목표에 연결된 항목. */
class CalendarRangeApiTests extends CalendarTestBase {

	@Autowired
	TrashPurgeService purge;

	private MvcResult list(String query) throws Exception {
		return api.get("/api/calendar/items?" + query, 1L);
	}

	// ------------------------------------------------------------ 기간 경계 (Review Focus 2)

	@Test
	void returnsItemsOverlappingTheRangeOrderedByStartDateThenId() throws Exception {
		long inside = task("범위 안 마감", "2026-11-20", "");
		long straddlesFrom = event("앞에서 걸침", "2026-10-28", "2026-11-03", "");
		long straddlesTo = event("뒤로 걸침", "2026-12-04", "2026-12-10", "");
		long sameStartLaterId = event("같은 시작", "2026-11-20", null, "");
		long before = event("범위 전", "2026-10-20", "2026-10-31", "");
		long after = event("범위 후", "2026-12-06", null, "");
		long coversAll = event("전체를 덮음", "2026-10-01", "2027-01-31", "");

		MvcResult result = list("from=2026-11-01&to=2026-12-05");
		api.expect(result, 200,
				"$.data.length()", 5,
				"$.data[0].id", (int) coversAll,
				"$.data[1].id", (int) straddlesFrom,
				"$.data[2].id", (int) inside,
				"$.data[3].id", (int) sameStartLaterId,
				"$.data[4].id", (int) straddlesTo);
		assertThat(before).isLessThan(after);
	}

	@Test
	void rangeBoundariesAreInclusive() throws Exception {
		long endsOnFrom = event("from에 끝남", "2026-10-30", "2026-11-01", "");
		long endsBeforeFrom = event("from 전날 끝남", "2026-10-30", "2026-10-31", "");
		long startsOnTo = event("to에 시작", "2026-11-30", "2026-12-02", "");
		long startsAfterTo = event("to 다음날 시작", "2026-12-01", "2026-12-02", "");
		long taskOnFrom = task("from 마감", "2026-11-01", "");
		long taskOnTo = task("to 마감", "2026-11-30", "");
		long taskAfter = task("to 뒤 마감", "2026-12-01", "");

		MvcResult result = list("from=2026-11-01&to=2026-11-30");
		api.expect(result, 200, "$.data.length()", 4);
		String body = api.body(result);
		for (long included : new long[] {endsOnFrom, startsOnTo, taskOnFrom, taskOnTo}) {
			assertThat(body).contains("\"id\":" + included + ",");
		}
		for (long excluded : new long[] {endsBeforeFrom, startsAfterTo, taskAfter}) {
			assertThat(body).doesNotContain("\"id\":" + excluded + ",");
		}
	}

	@Test
	void hundredDaysIsAllowedAndHundredOneIsRejected() throws Exception {
		task("아무거나", "2026-11-10", "");
		// 2026-11-01 ~ 2027-02-08 = 100일(양 끝 포함)
		api.expect(list("from=2026-11-01&to=2027-02-08"), 200, "$.data.length()", 1);
		api.expect(list("from=2026-11-01&to=2027-02-09"), 422,
				"$.code", "REPORT_INVALID_PERIOD", "$.details.maxDays", 100);
		// 하루짜리 기간도 된다.
		api.expect(list("from=2026-11-10&to=2026-11-10"), 200, "$.data.length()", 1);
	}

	@Test
	void toBeforeFromIsRejected() throws Exception {
		api.expect(list("from=2026-11-10&to=2026-11-09"), 422, "$.code", "REPORT_INVALID_PERIOD");
	}

	@Test
	void fromAndToAreRequiredAndMustBeDates() throws Exception {
		api.expect(api.get("/api/calendar/items?to=2026-11-30", 1L), 400,
				"$.code", "VALIDATION_FAILED", "$.details.fieldErrors[0].field", "from");
		api.expect(api.get("/api/calendar/items?from=2026-11-01", 1L), 400,
				"$.details.fieldErrors[0].field", "to");
		api.expect(list("from=2026-13-01&to=2026-11-30"), 400,
				"$.details.fieldErrors[0].field", "from", "$.details.fieldErrors[0].reason", "INVALID_FORMAT");
		api.expect(list("from=2026-11-01&to=2026-11-30&yearlyGoalIds=abc"), 400,
				"$.details.fieldErrors[0].field", "yearlyGoalIds");
		api.expect(list("from=2026-11-01&to=2026-11-30&includeUnlinked=maybe"), 400,
				"$.details.fieldErrors[0].field", "includeUnlinked");
	}

	// ------------------------------------------------------------ 목표 필터

	@Test
	void goalFiltersCombineWithIncludeUnlinked() throws Exception {
		long y2 = api.yearly(1L, "영어 회화");
		long a = task("y1 항목", "2026-11-10", ",\"yearlyGoalId\":" + y1);
		long b = task("y2 항목", "2026-11-11", ",\"yearlyGoalId\":" + y2);
		long c = task("미연결 항목", "2026-11-12", "");
		String base = "from=2026-11-01&to=2026-11-30";

		assertIds(list(base), a, b, c);
		assertIds(list(base + "&includeUnlinked=false"), a, b);
		assertIds(list(base + "&yearlyGoalIds=" + y1), a, c);
		assertIds(list(base + "&yearlyGoalIds=" + y1 + "&includeUnlinked=false"), a);
		assertIds(list(base + "&yearlyGoalIds=" + y1 + "," + y2 + "&includeUnlinked=false"), a, b);
		assertIds(list(base + "&yearlyGoalIds=" + y2 + "&includeUnlinked=true"), b, c);
		// 목록에 없는 목표만 고르면 미연결 항목만 남는다.
		assertIds(list(base + "&yearlyGoalIds=999999"), c);
		// 값 없는 yearlyGoalIds는 "고른 목표 없음"으로 본다(가정). 미연결만 남는다.
		assertIds(list(base + "&yearlyGoalIds="), c);
		assertIds(list(base + "&yearlyGoalIds=&includeUnlinked=false"));
	}

	private void assertIds(MvcResult result, long... ids) throws Exception {
		api.expect(result, 200, "$.data.length()", ids.length);
		for (int i = 0; i < ids.length; i++) {
			api.expect(result, 200, "$.data[" + i + "].id", (int) ids[i]);
		}
	}

	@Test
	void itemsOfOtherGoalsOwnersAreNeverShown() throws Exception {
		task("내 항목", "2026-11-10", "");
		item(2L, "{\"itemType\":\"TASK\",\"title\":\"남의 항목\",\"startDate\":\"2026-11-10\"}");
		api.expect(list("from=2026-11-01&to=2026-11-30"), 200, "$.data.length()", 1, "$.data[0].title", "내 항목");
	}

	// ------------------------------------------------------------ 삭제된 목표에 연결된 항목 (Review Focus 3)

	@Test
	void itemLinkedToDeletedGoalLooksUnlinkedUntilRestored() throws Exception {
		long y2 = api.yearly(1L, "남는 목표");
		long linked = task("삭제될 목표의 항목", "2026-11-10", ",\"yearlyGoalId\":" + y1);
		long other = task("다른 목표의 항목", "2026-11-11", ",\"yearlyGoalId\":" + y2);
		String base = "from=2026-11-01&to=2026-11-30";
		api.expect(api.delete("/api/goals/yearly/" + y1 + "?confirm=true", 1L), 200);

		// 응답의 yearlyGoal은 null이다(연결 행은 그대로).
		MvcResult all = list(base);
		api.expect(all, 200, "$.data.length()", 2,
				"$.data[0].id", (int) linked, "$.data[0].yearlyGoal", null,
				"$.data[1].id", (int) other, "$.data[1].yearlyGoal.id", (int) y2);
		assertThat(jdbc.queryForObject("SELECT yearly_goal_id FROM calendar_items WHERE id = ?", Long.class, linked))
				.isEqualTo(y1);

		// 삭제된 목표 id로는 걸리지 않고, 미연결로 취급되어 includeUnlinked에 따른다.
		assertIds(list(base + "&yearlyGoalIds=" + y1 + "&includeUnlinked=false"));
		assertIds(list(base + "&yearlyGoalIds=" + y1), linked);
		assertIds(list(base + "&yearlyGoalIds=" + y2 + "&includeUnlinked=false"), other);
		assertIds(list(base + "&includeUnlinked=false"), other);

		// 목표를 복원하면 다시 연결돼 보인다.
		api.expect(api.post("/api/goals/yearly/" + y1 + "/restore", 1L, null), 200);
		api.expect(list(base), 200, "$.data[0].id", (int) linked, "$.data[0].yearlyGoal.id", (int) y1);
		assertIds(list(base + "&yearlyGoalIds=" + y1 + "&includeUnlinked=false"), linked);
	}

	@Test
	void permanentPurgeOfAGoalDoesNotBlockOnLinkedCalendarItems() throws Exception {
		long linked = task("연결 항목", "2026-11-10", ",\"yearlyGoalId\":" + y1);
		api.expect(api.delete("/api/goals/yearly/" + y1 + "?confirm=true", 1L), 200);
		// 삭제 시각을 31일 전으로 밀어 정리 대상으로 만든다.
		jdbc.update("UPDATE yearly_goals SET deleted_at = ? WHERE id = ?",
				Timestamp.valueOf(LocalDateTime.ofInstant(Instant.parse("2026-10-01T00:00:00Z"), ZoneOffset.UTC)), y1);

		TrashPurgeService.Summary summary = purge.purgeExpired();

		assertThat(summary.failed()).isZero();
		assertThat(count("SELECT COUNT(*) FROM yearly_goals WHERE id = ?", y1)).isZero();
		// 달력 항목은 지워지지 않고 목표 없는 항목으로 남는다(FK ON DELETE SET NULL).
		assertThat(count("SELECT COUNT(*) FROM calendar_items WHERE id = ?", linked)).isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT yearly_goal_id FROM calendar_items WHERE id = ?", Long.class, linked))
				.isNull();
		api.expect(list("from=2026-11-01&to=2026-11-30&includeUnlinked=false"), 200, "$.data.length()", 0);
		api.expect(list("from=2026-11-01&to=2026-11-30"), 200, "$.data.length()", 1, "$.data[0].yearlyGoal", null);
	}
}
