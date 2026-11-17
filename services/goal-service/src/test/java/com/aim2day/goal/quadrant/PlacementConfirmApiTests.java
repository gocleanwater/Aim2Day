package com.aim2day.goal.quadrant;

import static org.assertj.core.api.Assertions.assertThat;

import com.aim2day.goal.support.GoalTestSupport;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class PlacementConfirmApiTests extends QuadrantTestBase {

	private Long confirmedDate() {
		return jdbc.query("SELECT last_quadrant_confirmed_date FROM user_settings WHERE user_id = 1",
				rs -> rs.next() ? (rs.getDate(1) == null ? null : rs.getDate(1).toLocalDate().toEpochDay()) : null);
	}

	private long insertRow(long actionId, LocalDate date, Integer pendingX, boolean unclassify) {
		jdbc.update("INSERT INTO action_quadrant_placements (action_id, classification_date, importance_x, urgency_y, "
				+ "pending_importance_x, pending_urgency_y, is_pending_unclassify) VALUES (?, ?, 10, 10, ?, ?, ?)",
				actionId, java.sql.Date.valueOf(date), pendingX, pendingX, unclassify);
		return actionId;
	}

	@Test
	void confirmsPendingAndRemovesUnclassifyWaiting() throws Exception {
		long fresh = api.maintenance(1L, "새 점", "");
		long moved = api.maintenance(1L, "옮긴 점", "");
		long gone = api.maintenance(1L, "트레이로", "");
		long tray = api.maintenance(1L, "원래 트레이", "");
		long stay = api.maintenance(1L, "그대로 확정", "");
		put(moved, 80, 90);
		put(gone, 70, 70);
		put(stay, 55, 55);
		confirm();

		put(fresh, 51, 52);
		put(moved, 60, 30);
		putTray(gone);

		api.expect(confirm(), 200, "$.data.date", "2026-11-12", "$.data.confirmedCount", 2,
				"$.data.unclassifiedCount", 1);
		api.expect(api.get("/api/actions?sort=GROUP", 1L), 200,
				"$.data.items[0].placement.state", "CONFIRMED", "$.data.items[0].placement.confirmed.importance", 51,
				"$.data.items[1].placement.state", "CONFIRMED", "$.data.items[1].placement.confirmed.importance", 60,
				"$.data.items[1].placement.confirmed.urgency", 30, "$.data.items[1].placement.pending", null,
				"$.data.items[2].placement.state", "UNCLASSIFIED",
				"$.data.items[3].placement.state", "UNCLASSIFIED",
				"$.data.items[4].placement.state", "CONFIRMED", "$.data.items[4].placement.confirmed.importance", 55);
		assertThat(rows(gone)).isZero();
		assertThat(rows(tray)).isZero();
		assertThat(jdbc.queryForObject(
				"SELECT confirmed_at FROM action_quadrant_placements WHERE action_id = ?", Object.class, fresh))
				.isNotNull();
		// 확정은 한 번 더 눌러도 변경이 없다.
		api.expect(confirm(), 200, "$.data.confirmedCount", 0, "$.data.unclassifiedCount", 0);
	}

	@Test
	void zeroChangeConfirmStillRecordsConfirmedToday() throws Exception {
		api.maintenance(1L, "점 없음", "");
		api.expect(api.get("/api/actions", 1L), 200, "$.data.classification.confirmedToday", false);
		// 설정 행이 아직 없어도(목록 조회가 만들 수 있다) 확정이 성공한다. 설정 version은 오르지 않는다.
		api.expect(api.get("/api/settings", 1L), 200, "$.data.version", 0);
		api.expect(confirm(), 200, "$.data.confirmedCount", 0, "$.data.unclassifiedCount", 0);
		assertThat(confirmedDate()).isEqualTo(LocalDate.of(2026, 11, 12).toEpochDay());
		api.expect(api.get("/api/actions", 1L), 200, "$.data.classification.confirmedToday", true);
		// 다른 날짜를 조회해도 confirmedToday는 "오늘 눌렀는지"다.
		api.expect(api.get("/api/actions?date=2026-11-11", 1L), 200, "$.data.classification.confirmedToday", true);
		api.expect(api.get("/api/settings", 1L), 200, "$.data.version", 0);
		// 다음 날이 되면 다시 false다.
		tomorrow();
		api.expect(api.get("/api/actions", 1L), 200, "$.data.classification.confirmedToday", false);
	}

	@Test
	void confirmWorksWhenSettingsRowDoesNotExistYet() throws Exception {
		// 목표 준비 과정이 설정 행을 만들었으므로 지운다.
		jdbc.update("DELETE FROM user_settings");
		api.expect(confirm(), 200, "$.data.confirmedCount", 0);
		assertThat(confirmedDate()).isEqualTo(LocalDate.of(2026, 11, 12).toEpochDay());
	}

	@Test
	void leavesOtherRowsUntouched() throws Exception {
		LocalDate yesterday = LocalDate.of(2026, 11, 11);
		// 어제 수행일 행동의 어제자 미확정 행.
		long pastAction = GoalTestSupport.seedAction(jdbc, 1L, null, "어제", yesterday, false, false);
		insertRow(pastAction, yesterday, 70, false);
		// 오늘 수행일이지만 어제자 행만 있는 행동(수행일을 어제에서 오늘로 옮긴 경우).
		long movedAction = GoalTestSupport.seedAction(jdbc, 1L, null, "옮김", LocalDate.of(2026, 11, 12), false, false);
		insertRow(movedAction, yesterday, 70, false);
		// 오늘자 행이지만 수행일이 오늘이 아닌 행동.
		long notToday = GoalTestSupport.seedAction(jdbc, 1L, null, "수행일 바뀜", yesterday, false, false);
		insertRow(notToday, LocalDate.of(2026, 11, 12), 70, false);
		// 삭제된 행동, 경로가 삭제된 미완료 행동, 다른 사용자의 행동.
		long deleted = GoalTestSupport.seedAction(jdbc, 1L, null, "삭제", LocalDate.of(2026, 11, 12), false, true);
		insertRow(deleted, LocalDate.of(2026, 11, 12), 70, false);
		long hiddenWeekly = GoalTestSupport.seedWeekly(jdbc, monthly, LocalDate.of(2026, 11, 8),
				LocalDate.of(2026, 11, 14), "숨김 주간", "CHECKLIST", null);
		jdbc.update("UPDATE weekly_goals SET deleted_at = CURRENT_TIMESTAMP WHERE id = ?", hiddenWeekly);
		long hidden = GoalTestSupport.seedAction(jdbc, 1L, hiddenWeekly, "숨김", LocalDate.of(2026, 11, 12), false, false);
		insertRow(hidden, LocalDate.of(2026, 11, 12), 70, false);
		long others = GoalTestSupport.seedAction(jdbc, 2L, null, "남의 것", LocalDate.of(2026, 11, 12), false, false);
		insertRow(others, LocalDate.of(2026, 11, 12), 70, false);
		// 대조: 오늘 보이는 행동의 행은 확정된다.
		long visible = GoalTestSupport.seedAction(jdbc, 1L, null, "보임", LocalDate.of(2026, 11, 12), false, false);
		insertRow(visible, LocalDate.of(2026, 11, 12), 70, false);

		api.expect(confirm(), 200, "$.data.confirmedCount", 1, "$.data.unclassifiedCount", 0);
		for (long untouched : new long[] {pastAction, movedAction, notToday, deleted, hidden, others}) {
			assertThat(jdbc.queryForObject(
					"SELECT pending_importance_x FROM action_quadrant_placements WHERE action_id = ?", Integer.class,
					untouched)).as("action " + untouched).isEqualTo(70);
		}
		assertThat(jdbc.queryForObject(
				"SELECT importance_x FROM action_quadrant_placements WHERE action_id = ?", Integer.class, visible))
				.isEqualTo(70);
		assertThat(jdbc.queryForObject(
				"SELECT pending_importance_x FROM action_quadrant_placements WHERE action_id = ?", Integer.class,
				visible)).isNull();
	}

	@Test
	void confirmIncludesCompletedActionsUnderDeletedPath() throws Exception {
		// 상위 목표가 삭제돼도 완료 행동은 오늘 목록에 남으므로 확정 대상이다(API 명세 §4.10, 2026-11-17 사용자 결정).
		LocalDate today = LocalDate.of(2026, 11, 12);
		long hiddenWeekly = GoalTestSupport.seedWeekly(jdbc, monthly, LocalDate.of(2026, 11, 8),
				LocalDate.of(2026, 11, 14), "숨김 주간", "CHECKLIST", null);
		long moved = GoalTestSupport.seedAction(jdbc, 1L, hiddenWeekly, "완료·이동", today, true, false);
		long toTray = GoalTestSupport.seedAction(jdbc, 1L, hiddenWeekly, "완료·트레이", today, true, false);
		insertRow(moved, today, 70, false);
		insertRow(toTray, today, null, true);
		jdbc.update("UPDATE weekly_goals SET deleted_at = CURRENT_TIMESTAMP WHERE id = ?", hiddenWeekly);

		api.expect(confirm(), 200, "$.data.confirmedCount", 1, "$.data.unclassifiedCount", 1);
		assertThat(jdbc.queryForObject(
				"SELECT importance_x FROM action_quadrant_placements WHERE action_id = ?", Integer.class, moved))
				.isEqualTo(70);
		assertThat(jdbc.queryForObject(
				"SELECT pending_importance_x FROM action_quadrant_placements WHERE action_id = ?", Integer.class,
				moved)).isNull();
		assertThat(rows(toTray)).isZero();
	}

	@Test
	void otherUsersConfirmDoesNotAffectMine() throws Exception {
		long mine = api.maintenance(1L, "내 점", "");
		put(mine, 70, 70);
		api.expect(api.post("/api/actions/placements/confirm", 2L, null), 200, "$.data.confirmedCount", 0);
		api.expect(api.get("/api/actions", 1L), 200, "$.data.items[0].placement.state", "PENDING");
	}

	@Test
	void classificationCountsFollowConfirm() throws Exception {
		long a = api.maintenance(1L, "a", "");
		api.maintenance(1L, "b", "");
		put(a, 70, 70);
		api.expect(api.get("/api/actions", 1L), 200,
				"$.data.classification.unclassified", 1, "$.data.classification.unconfirmedChanges", 1,
				"$.data.classification.confirmed", 0, "$.data.classification.confirmedToday", false);
		confirm();
		api.expect(api.get("/api/actions", 1L), 200,
				"$.data.classification.unclassified", 1, "$.data.classification.unconfirmedChanges", 0,
				"$.data.classification.confirmed", 1, "$.data.classification.confirmedToday", true);
	}
}
