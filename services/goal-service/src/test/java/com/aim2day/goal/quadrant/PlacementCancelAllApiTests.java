package com.aim2day.goal.quadrant;

import static org.assertj.core.api.Assertions.assertThat;

import com.aim2day.goal.support.GoalTestSupport;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class PlacementCancelAllApiTests extends QuadrantTestBase {

	private static final LocalDate TODAY = LocalDate.of(2026, 11, 12);

	private Object confirmedDate() {
		return jdbc.query("SELECT last_quadrant_confirmed_date FROM user_settings WHERE user_id = 1",
				rs -> rs.next() ? rs.getDate(1) : null);
	}

	private void insertRow(long actionId, LocalDate date, Integer pendingX, boolean unclassify) {
		jdbc.update("INSERT INTO action_quadrant_placements (action_id, classification_date, importance_x, urgency_y, "
				+ "pending_importance_x, pending_urgency_y, is_pending_unclassify) VALUES (?, ?, 10, 10, ?, ?, ?)",
				actionId, java.sql.Date.valueOf(date), pendingX, pendingX, unclassify);
	}

	@Test
	void cancelsMixedStatesAndKeepsConfirmedTodayRecord() throws Exception {
		long reclassifying = api.maintenance(1L, "재분류", "");
		long pendingOnly = api.maintenance(1L, "새 점", "");
		long unclassify = api.maintenance(1L, "트레이 대기", "");
		long confirmedOnly = api.maintenance(1L, "확정만", "");
		put(reclassifying, 80, 90);
		put(unclassify, 70, 70);
		put(confirmedOnly, 55, 55);
		confirm();
		Object before = confirmedDate();
		assertThat(before).isNotNull();

		put(reclassifying, 60, 30);
		put(pendingOnly, 51, 52);
		putTray(unclassify);

		api.expect(api.delete("/api/actions/placements/pending", 1L), 200, "$.data.date", "2026-11-12",
				"$.data.cancelledCount", 3);

		api.expect(api.get("/api/actions?sort=GROUP", 1L), 200,
				"$.data.items[0].placement.state", "CONFIRMED", "$.data.items[0].placement.confirmed.importance", 80,
				"$.data.items[0].placement.pending", null,
				"$.data.items[1].placement.state", "UNCLASSIFIED",
				"$.data.items[2].placement.state", "CONFIRMED", "$.data.items[2].placement.confirmed.importance", 70,
				"$.data.items[3].placement.state", "CONFIRMED", "$.data.items[3].placement.confirmed.importance", 55);
		assertThat(rows(pendingOnly)).isZero();
		assertThat(pendingUnclassify(unclassify)).isFalse();
		assertThat(confirmedDate()).isEqualTo(before);

		// 다시 눌러도 변경이 없다.
		api.expect(api.delete("/api/actions/placements/pending", 1L), 200, "$.data.cancelledCount", 0);
	}

	/** 주간 목표 하나를 시드하고 소프트 삭제한다. 그 아래 행동은 경로 삭제 상태가 된다. */
	private long deletedWeekly() {
		long yearly = GoalTestSupport.seedYearly(jdbc, 1L, 2026, "연간");
		long monthly = GoalTestSupport.seedMonthly(jdbc, yearly, LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30),
				"월간");
		long weekly = GoalTestSupport.seedWeekly(jdbc, monthly, LocalDate.of(2026, 11, 9), LocalDate.of(2026, 11, 15),
				"주간", "CHECK", null);
		jdbc.update("UPDATE weekly_goals SET deleted_at = ? WHERE id = ?",
				java.sql.Timestamp.valueOf(java.time.LocalDateTime.of(2026, 11, 12, 3, 0)), weekly);
		return weekly;
	}

	private Integer pendingX(long actionId) {
		return jdbc.queryForObject("SELECT pending_importance_x FROM action_quadrant_placements WHERE action_id = ?",
				Integer.class, actionId);
	}

	@Test
	void cancelsCompletedActionWhoseParentGoalWasDeleted() throws Exception {
		long weekly = deletedWeekly();
		// 상위 목표가 삭제돼도 완료 행동은 오늘 목록에 남으므로 취소 대상이다.
		long completed = GoalTestSupport.seedAction(jdbc, 1L, weekly, "완료됨", TODAY, true, false);
		insertRow(completed, TODAY, 70, false);

		api.expect(api.delete("/api/actions/placements/pending", 1L), 200, "$.data.cancelledCount", 1);

		assertThat(pendingX(completed)).isNull();
	}

	@Test
	void leavesIncompleteActionWhoseParentGoalWasDeleted() throws Exception {
		long weekly = deletedWeekly();
		// 미완료 행동은 오늘 목록에서 숨겨지므로 건드리지 않는다.
		long hidden = GoalTestSupport.seedAction(jdbc, 1L, weekly, "숨김", TODAY, false, false);
		insertRow(hidden, TODAY, 70, false);

		api.expect(api.delete("/api/actions/placements/pending", 1L), 200, "$.data.cancelledCount", 0);

		assertThat(pendingX(hidden)).isEqualTo(70);
	}

	@Test
	void leavesActionWhosePerformedDateMovedOffToday() throws Exception {
		// 오늘 배치 후 수행일을 옮기면 오늘자 행은 남는다(수행일과 분류일이 어긋난 상태).
		long moved = GoalTestSupport.seedAction(jdbc, 1L, null, "옮김", TODAY, false, false);
		insertRow(moved, TODAY, 70, false);
		jdbc.update("UPDATE actions SET performed_date = ? WHERE id = ?", java.sql.Date.valueOf(TODAY.plusDays(1)),
				moved);

		api.expect(api.delete("/api/actions/placements/pending", 1L), 200, "$.data.cancelledCount", 0);

		assertThat(pendingX(moved)).isEqualTo(70);
	}

	@Test
	void emptyReturnsZero() throws Exception {
		api.maintenance(1L, "점 없음", "");
		api.expect(api.delete("/api/actions/placements/pending", 1L), 200, "$.data.date", "2026-11-12",
				"$.data.cancelledCount", 0);
		// 확정 기록을 만들지 않는다.
		assertThat(confirmedDate()).isNull();
	}

	@Test
	void leavesOtherDaysAndOtherUsersUntouched() throws Exception {
		LocalDate yesterday = LocalDate.of(2026, 11, 11);
		long pastAction = GoalTestSupport.seedAction(jdbc, 1L, null, "어제", yesterday, false, false);
		insertRow(pastAction, yesterday, 70, false);
		long others = GoalTestSupport.seedAction(jdbc, 2L, null, "남의 것", TODAY, false, false);
		insertRow(others, TODAY, 70, false);
		long mine = GoalTestSupport.seedAction(jdbc, 1L, null, "내 것", TODAY, false, false);
		insertRow(mine, TODAY, 70, false);

		api.expect(api.delete("/api/actions/placements/pending", 1L), 200, "$.data.cancelledCount", 1);

		assertThat(jdbc.queryForObject(
				"SELECT pending_importance_x FROM action_quadrant_placements WHERE action_id = ?", Integer.class,
				pastAction)).isEqualTo(70);
		assertThat(jdbc.queryForObject(
				"SELECT pending_importance_x FROM action_quadrant_placements WHERE action_id = ?", Integer.class,
				others)).isEqualTo(70);
		assertThat(jdbc.queryForObject(
				"SELECT pending_importance_x FROM action_quadrant_placements WHERE action_id = ?", Integer.class, mine))
				.isNull();
		assertThat(jdbc.queryForObject(
				"SELECT importance_x FROM action_quadrant_placements WHERE action_id = ?", Integer.class, mine))
				.isEqualTo(10);
	}
}
