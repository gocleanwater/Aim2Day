package com.aim2day.goal.quadrant;

import static org.assertj.core.api.Assertions.assertThat;

import com.aim2day.goal.support.GoalTestSupport;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class PlacementApiTests extends QuadrantTestBase {

	@Test
	void placeCreatesPendingAndResponseShape() throws Exception {
		long a = api.maintenance(1L, "청소", "");
		api.expect(put(a, 70, 60), 200,
				"$.data.actionId", (int) a,
				"$.data.placement.state", "PENDING",
				"$.data.placement.confirmed", null,
				"$.data.placement.pending.importance", 70,
				"$.data.placement.pending.urgency", 60,
				"$.data.placement.displayed.importance", 70,
				"$.data.placement.displayed.urgency", 60);
		assertThat(rows(a)).isEqualTo(1);
		// 같은 행동을 다시 놓아도 행은 하나이고 마지막 값이 남는다.
		api.expect(put(a, 10, 20), 200, "$.data.placement.pending.importance", 10);
		assertThat(rows(a)).isEqualTo(1);
		// 목록의 행동 객체에도 같은 placement가 실린다.
		api.expect(api.get("/api/actions", 1L), 200,
				"$.data.items[0].placement.state", "PENDING", "$.data.items[0].placement.displayed.urgency", 20);
	}

	@Test
	void stateFlowFromErdTable() throws Exception {
		long a = api.maintenance(1L, "보고서", "");
		// 미분류 -> 미확정 -> 확정
		api.expect(put(a, 80, 90), 200, "$.data.placement.state", "PENDING");
		api.expect(confirm(), 200, "$.data.confirmedCount", 1, "$.data.unclassifiedCount", 0);
		api.expect(api.get("/api/actions", 1L), 200,
				"$.data.items[0].placement.state", "CONFIRMED",
				"$.data.items[0].placement.confirmed.importance", 80,
				"$.data.items[0].placement.pending", null,
				"$.data.items[0].placement.displayed.urgency", 90);

		// 확정 -> 재분류 중: 확정 좌표는 그대로, 화면의 점은 미확정 위치다.
		api.expect(put(a, 60, 30), 200,
				"$.data.placement.state", "RECLASSIFYING",
				"$.data.placement.confirmed.importance", 80, "$.data.placement.confirmed.urgency", 90,
				"$.data.placement.pending.importance", 60,
				"$.data.placement.displayed.importance", 60, "$.data.placement.displayed.urgency", 30);

		// 재분류 중 -> 되돌리기 대기: 점은 트레이로 가지만 확정 좌표는 남는다.
		api.expect(putTray(a), 200,
				"$.data.placement.state", "PENDING_UNCLASSIFY",
				"$.data.placement.confirmed.importance", 80,
				"$.data.placement.pending", null,
				"$.data.placement.displayed", null);
		assertThat(pendingUnclassify(a)).isTrue();

		// 되돌리기 대기에서 좌표를 놓으면 대기가 풀리고 새 미확정 좌표가 생긴다.
		api.expect(put(a, 55, 45), 200, "$.data.placement.state", "RECLASSIFYING");
		assertThat(pendingUnclassify(a)).isFalse();

		// 변경 취소: 미확정만 지우고 확정 좌표로 돌아간다.
		api.expect(cancel(a), 200,
				"$.data.actionId", (int) a,
				"$.data.placement.state", "CONFIRMED", "$.data.placement.displayed.importance", 80);

		// 되돌리기 대기를 취소해도 확정 좌표로 돌아간다.
		api.expect(putTray(a), 200, "$.data.placement.state", "PENDING_UNCLASSIFY");
		api.expect(cancel(a), 200, "$.data.placement.state", "CONFIRMED");

		// 되돌리기 대기를 확정하면 행이 지워져 미분류가 된다.
		api.expect(putTray(a), 200, "$.data.placement.state", "PENDING_UNCLASSIFY");
		api.expect(confirm(), 200, "$.data.confirmedCount", 0, "$.data.unclassifiedCount", 1);
		assertThat(rows(a)).isZero();
		api.expect(api.get("/api/actions", 1L), 200, "$.data.items[0].placement.state", "UNCLASSIFIED");
	}

	@Test
	void nullHandlingThreeCases() throws Exception {
		long a = api.maintenance(1L, "청소", "");
		// 1) 이미 트레이(행 없음): 아무것도 바꾸지 않고 200.
		api.expect(putTray(a), 200, "$.data.placement.state", "UNCLASSIFIED");
		assertThat(rows(a)).isZero();

		// 2) 확정 좌표가 없으면(미확정) 행을 지워 바로 미분류.
		api.expect(put(a, 40, 40), 200, "$.data.placement.state", "PENDING");
		api.expect(putTray(a), 200, "$.data.placement.state", "UNCLASSIFIED", "$.data.placement.displayed", null);
		assertThat(rows(a)).isZero();

		// 3) 확정 좌표가 있으면 되돌리기 대기. 이미 대기 중이면 그대로 200.
		put(a, 40, 40);
		confirm();
		api.expect(putTray(a), 200, "$.data.placement.state", "PENDING_UNCLASSIFY");
		api.expect(putTray(a), 200, "$.data.placement.state", "PENDING_UNCLASSIFY",
				"$.data.placement.confirmed.importance", 40);
		assertThat(rows(a)).isEqualTo(1);
	}

	@Test
	void validationFailsBeforeLookup() throws Exception {
		long a = api.maintenance(1L, "청소", "");
		String[] bad = {
				"{}", "{\"importance\":50}", "{\"urgency\":50}",
				"{\"importance\":null,\"urgency\":50}", "{\"importance\":50,\"urgency\":null}",
				"{\"importance\":101,\"urgency\":0}", "{\"importance\":0,\"urgency\":-1}",
				"{\"importance\":72.5,\"urgency\":10}", "{\"importance\":72.0,\"urgency\":10}",
				"{\"importance\":\"70\",\"urgency\":10}", "{\"importance\":true,\"urgency\":10}",
				"{\"importance\":99999999999999999999,\"urgency\":10}"
		};
		for (String body : bad) {
			api.expect(put(1L, a, body), 400, "$.code", "VALIDATION_FAILED");
			// 검증이 404보다 먼저다.
			api.expect(put(1L, a + 9999, body), 400, "$.code", "VALIDATION_FAILED");
		}
		api.expect(put(1L, a, "{\"importance\":null}"), 400,
				"$.details.fieldErrors[0].field", "urgency", "$.details.fieldErrors[0].reason", "REQUIRED");
		api.expect(put(1L, a, "{\"importance\":101,\"urgency\":0}"), 400,
				"$.details.fieldErrors[0].field", "importance", "$.details.fieldErrors[0].reason", "OUT_OF_RANGE");
		assertThat(rows(a)).isZero();
		// 경계값 0과 100은 통과한다(MAINTENANCE).
		api.expect(put(1L, a, "{\"importance\":0,\"urgency\":100}"), 200, "$.data.placement.state", "PENDING");
	}

	@Test
	void notFoundCases() throws Exception {
		long other = api.maintenance(2L, "남의 것", "");
		long deleted = api.maintenance(1L, "지움", "");
		api.expect(api.delete("/api/actions/" + deleted, 1L), 200);
		long pathGone = api.goalAction(1L, w1, "경로 삭제", "");
		jdbc.update("UPDATE weekly_goals SET deleted_at = CURRENT_TIMESTAMP WHERE id = ?", w1);
		long missing = other + 9999;
		for (long id : new long[] {other, deleted, pathGone, missing}) {
			api.expect(put(id, 70, 60), 404, "$.code", "RESOURCE_NOT_FOUND");
			api.expect(putTray(id), 404, "$.code", "RESOURCE_NOT_FOUND");
			api.expect(cancel(id), 404, "$.code", "RESOURCE_NOT_FOUND");
		}
		assertThat(rows(other)).isZero();
		// 404는 날짜 검사보다 먼저다: 남의 지난 행동도 422가 아니라 404다.
		long otherPast = api.maintenance(2L, "남의 어제", ",\"performedDate\":\"2026-11-11\"");
		api.expect(put(otherPast, 70, 60), 404, "$.code", "RESOURCE_NOT_FOUND");
	}

	@Test
	void dateMustBeToday() throws Exception {
		long past = api.maintenance(1L, "어제 것", ",\"performedDate\":\"2026-11-11\"");
		long future = api.maintenance(1L, "내일 것", ",\"performedDate\":\"2026-11-13\"");
		api.expect(put(past, 70, 60), 422,
				"$.code", "QUADRANT_DATE_INVALID", "$.details.today", "2026-11-12",
				"$.details.performedDate", "2026-11-11");
		api.expect(put(future, 70, 60), 422, "$.code", "QUADRANT_DATE_INVALID", "$.details.performedDate", "2026-11-13");
		api.expect(putTray(past), 422, "$.code", "QUADRANT_DATE_INVALID");
		api.expect(cancel(past), 422, "$.code", "QUADRANT_DATE_INVALID");
		// 날짜 검사가 중요도 범위 검사보다 먼저다.
		long goalPast = GoalTestSupport.seedAction(jdbc, 1L, w1, "지난 핵심", LocalDate.of(2026, 11, 11), false, false);
		api.expect(put(goalPast, 10, 10), 422, "$.code", "QUADRANT_DATE_INVALID");
		assertThat(rows(past) + rows(future) + rows(goalPast)).isZero();
	}

	@Test
	void goalImportanceLowerBound() throws Exception {
		long goal = api.goalAction(1L, w1, "핵심", "");
		long maintenance = api.maintenance(1L, "그 밖", "");
		// GOAL x=49 -> 422, x=50 -> 200.
		api.expect(put(goal, 49, 100), 422,
				"$.code", "QUADRANT_OUT_OF_RANGE", "$.details.minImportance", 50);
		assertThat(rows(goal)).isZero();
		api.expect(put(goal, 50, 0), 200, "$.data.placement.state", "PENDING");
		// 긴급도는 제한이 없다(0~100).
		api.expect(put(goal, 100, 0), 200);
		// MAINTENANCE x=0 -> 200.
		api.expect(put(maintenance, 0, 0), 200, "$.data.placement.displayed.importance", 0);
		// GOAL을 트레이로 되돌리는 것은 제한이 없다.
		api.expect(putTray(goal), 200, "$.data.placement.state", "UNCLASSIFIED");
	}

	@Test
	void completedActionCanBeClassifiedAndStaysCompleted() throws Exception {
		long a = api.goalAction(1L, w1, "끝낸 일", "");
		api.expect(api.complete(1L, a, true), 200);
		api.expect(put(a, 90, 10), 200, "$.data.placement.state", "PENDING");
		api.expect(api.get("/api/actions", 1L), 200,
				"$.data.items[0].isCompleted", true, "$.data.items[0].placement.state", "PENDING");
		// 좌표는 주간 진행률 등 다른 값을 바꾸지 않는다.
		assertThat(jdbc.queryForObject("SELECT is_completed FROM actions WHERE id = ?", Boolean.class, a)).isTrue();
	}

	/** Review Focus 3: 어제의 미확정 좌표는 어제 조회에서 보이지 않고, 오늘 확정이 어제 행을 건드리지 않는다. */
	@Test
	void yesterdayPendingIsIgnoredAndNotConfirmed() throws Exception {
		long pendingOnly = api.maintenance(1L, "미확정만", "");
		long reclassify = api.maintenance(1L, "재분류 중", "");
		long unclassify = api.maintenance(1L, "되돌리기 대기", "");
		put(reclassify, 80, 90);
		put(unclassify, 70, 70);
		confirm();
		put(pendingOnly, 20, 20);
		put(reclassify, 60, 30);
		putTray(unclassify);

		tomorrow();
		// 오늘이 11-13이므로 11-12는 지난 날짜다. 확정 좌표만 보인다.
		api.expect(api.get("/api/actions?date=2026-11-12&sort=GROUP", 1L), 200,
				"$.data.items[0].id", (int) pendingOnly,
				"$.data.items[0].placement.state", "UNCLASSIFIED",
				"$.data.items[0].placement.pending", null,
				"$.data.items[1].placement.state", "CONFIRMED",
				"$.data.items[1].placement.confirmed.importance", 80,
				"$.data.items[1].placement.displayed.importance", 80,
				"$.data.items[1].placement.pending", null,
				"$.data.items[2].placement.state", "CONFIRMED",
				"$.data.items[2].placement.displayed.importance", 70,
				"$.data.classification.unconfirmedChanges", 0,
				"$.data.classification.unclassified", 1,
				"$.data.classification.confirmed", 2);

		// 오늘(11-13) 확정은 11-12 행을 건드리지 않는다.
		api.expect(confirm(), 200, "$.data.date", "2026-11-13", "$.data.confirmedCount", 0,
				"$.data.unclassifiedCount", 0);
		assertThat(jdbc.queryForObject(
				"SELECT pending_importance_x FROM action_quadrant_placements WHERE action_id = ?", Integer.class,
				reclassify)).isEqualTo(60);
		assertThat(pendingUnclassify(unclassify)).isTrue();
		assertThat(rows(pendingOnly)).isEqualTo(1);
		// 어제 날짜 행동은 오늘 분류할 수 없다.
		api.expect(put(reclassify, 70, 70), 422, "$.code", "QUADRANT_DATE_INVALID");
	}

	/** Review Focus 4: 수행일을 오늘로 옮긴 지난 행동은 오늘 미분류로 시작하고, 어제 확정 좌표는 어제 조회에 남는다. */
	@Test
	void movedActionStartsUnclassifiedToday() throws Exception {
		long a = api.maintenance(1L, "옮길 일", "");
		put(a, 80, 90);
		confirm();
		tomorrow();
		// 11-12 행동을 오늘(11-13)로 옮긴다.
		movePerformedDate(a, "2026-11-13");
		api.expect(api.get("/api/actions", 1L), 200,
				"$.data.date", "2026-11-13", "$.data.items[0].placement.state", "UNCLASSIFIED",
				"$.data.classification.unclassified", 1);
		// 오늘 새로 분류한다. 이전 날짜의 행은 그대로다.
		api.expect(put(a, 30, 30), 200, "$.data.placement.state", "PENDING");
		assertThat(rows(a)).isEqualTo(2);
		// 다시 11-12로 되돌리면 그날의 확정 좌표가 그대로 보인다.
		movePerformedDate(a, "2026-11-12");
		api.expect(api.get("/api/actions?date=2026-11-12", 1L), 200,
				"$.data.items[0].placement.state", "CONFIRMED",
				"$.data.items[0].placement.confirmed.importance", 80, "$.data.items[0].placement.pending", null);
	}

	private void movePerformedDate(long id, String date) throws Exception {
		int version = ((Number) GoalTestSupport.read(api.get("/api/actions?date="
				+ jdbc.queryForObject("SELECT performed_date FROM actions WHERE id = ?", String.class, id), 1L),
				"$.data.items[0].version")).intValue();
		api.expect(api.patch("/api/actions/" + id, 1L,
				"{\"performedDate\":\"" + date + "\",\"version\":" + version + "}"), 200);
	}

	@Test
	void unauthenticatedIsRejected() throws Exception {
		var result = mockMvc.perform(
				org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/actions/1/placement")
						.contentType("application/json").content("{\"importance\":1,\"urgency\":1}"))
				.andReturn();
		assertThat(result.getResponse().getStatus()).isIn(401, 403);
	}
}
