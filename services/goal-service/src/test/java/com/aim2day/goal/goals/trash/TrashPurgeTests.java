package com.aim2day.goal.goals.trash;

import static org.assertj.core.api.Assertions.assertThat;

import com.aim2day.goal.support.GoalTestSupport;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 30일 정리. 스케줄은 테스트 설정에서 꺼져 있으므로 서비스 메서드를 직접 부른다. H2의 FOR UPDATE SKIP LOCKED가
 * 같은 SQL로 동작해 우회 없이 같은 쿼리를 쓴다(실제 MySQL에서의 두 인스턴스 경합은 서버 E2E에서 확인한다).
 * 시계는 2026-11-12T03:00Z이고, "기한 지남"은 2026-09-01에 삭제한 것으로 만든다.
 */
class TrashPurgeTests extends TrashTestBase {

	private static final String OLD = "2026-09-01T00:00:00Z";
	private static final LocalDate DAY = LocalDate.of(2026, 11, 12);

	@Autowired
	TrashPurgeService purge;

	private long action(long weekly, boolean completed) {
		return GoalTestSupport.seedAction(jdbc, 1L, weekly, completed ? "완료" : "미완료", DAY, completed, false);
	}

	private void placement(long actionId) {
		jdbc.update("INSERT INTO action_quadrant_placements (action_id, classification_date, importance_x, urgency_y) "
				+ "VALUES (?, ?, 70, 70)", actionId, java.sql.Date.valueOf(DAY));
	}

	@Test
	void weeklyRootKeepsCompletedHistoryAndDropsEmptyPaths() throws Exception {
		// 완료 행동이 있는 주간: 미완료 행동만 지우고 주간은 보존
		long keepDone = GoalTestSupport.seedWeekly(jdbc, monthly, DAY, DAY.plusDays(2), "완료 있음", "CHECKLIST", null);
		long doneAction = action(keepDone, true);
		long openOfKeep = action(keepDone, false);
		// 수치 기록만 있는 주간: 보존
		long keepRecord = GoalTestSupport.seedWeekly(jdbc, monthly, DAY, DAY.plusDays(2), "기록 있음", "NUMERIC",
				new BigDecimal("5"));
		jdbc.update("INSERT INTO weekly_goal_numeric_records (weekly_goal_id, record_source, amount) "
				+ "VALUES (?, 'DIRECT_INPUT', 2)", keepRecord);
		// 미완료 행동만 있는 주간: 행동과 좌표(CASCADE)와 주간이 모두 사라진다
		long dropOpen = GoalTestSupport.seedWeekly(jdbc, monthly, DAY, DAY.plusDays(2), "미완료만", "CHECKLIST", null);
		long openOnly = action(dropOpen, false);
		placement(openOnly);
		// 빈 주간: 사라진다
		long dropEmpty = GoalTestSupport.seedWeekly(jdbc, monthly, DAY, DAY.plusDays(2), "빈 주간", "CHECKLIST", null);
		for (long w : new long[] {keepDone, keepRecord, dropOpen, dropEmpty}) {
			setDeletedAt("weekly_goals", w, OLD);
		}
		int logsBefore = count("SELECT COUNT(*) FROM goal_progress_logs");
		jdbc.update("INSERT INTO goal_progress_logs (user_id, target_type, target_id, previous_rate, new_rate, log_date) "
				+ "VALUES (1, 'WEEKLY_GOAL', ?, 0, 50, ?)", dropEmpty, java.sql.Date.valueOf(DAY));

		TrashPurgeService.Summary summary = purge.purgeExpired();

		assertThat(summary.roots()).isEqualTo(4);
		assertThat(summary.processed()).isEqualTo(4);
		assertThat(summary.deletedGoals()).isEqualTo(2);
		assertThat(summary.preservedGoals()).isEqualTo(2);
		assertThat(summary.deletedActions()).isEqualTo(2);
		assertThat(exists("weekly_goals", keepDone)).isTrue();
		assertThat(isDeleted("weekly_goals", keepDone)).as("보존된 행은 deleted_at을 유지한다").isTrue();
		assertThat(exists("actions", doneAction)).isTrue();
		assertThat(exists("actions", openOfKeep)).isFalse();
		assertThat(exists("weekly_goals", keepRecord)).isTrue();
		assertThat(count("SELECT COUNT(*) FROM weekly_goal_numeric_records WHERE weekly_goal_id = ?", keepRecord))
				.isEqualTo(1);
		assertThat(exists("weekly_goals", dropOpen)).isFalse();
		assertThat(exists("actions", openOnly)).isFalse();
		assertThat(count("SELECT COUNT(*) FROM action_quadrant_placements")).isZero();
		assertThat(exists("weekly_goals", dropEmpty)).isFalse();
		// 진행 기록은 폴리모픽 참조라 목표가 사라져도 그대로다.
		assertThat(count("SELECT COUNT(*) FROM goal_progress_logs")).isEqualTo(logsBefore + 1);
		// 삭제되지 않은 형제 목표와 부모는 그대로다.
		assertThat(exists("weekly_goals", w1)).isTrue();
		assertThat(exists("monthly_goals", monthly)).isTrue();
	}

	@Test
	void yearlyRootWithOnlyIncompleteWorkIsRemovedCompletely() throws Exception {
		api.goalAction(1L, w1, "미완료 1", "");
		api.goalAction(1L, n1, "미완료 2", ",\"contributionValue\":1");
		jdbc.update("INSERT INTO action_quadrant_placements (action_id, classification_date, importance_x, urgency_y) "
				+ "SELECT id, ?, 60, 60 FROM actions", java.sql.Date.valueOf(DAY));
		api.expect(delete(1L, "yearly", yearly, true), 200);
		setDeletedAt("yearly_goals", yearly, OLD);
		long other = api.yearly(1L, "남는 연간");
		int logs = count("SELECT COUNT(*) FROM goal_progress_logs");

		TrashPurgeService.Summary summary = purge.purgeExpired();

		assertThat(summary.roots()).isEqualTo(1);
		assertThat(summary.deletedGoals()).isEqualTo(4); // 주간 2 + 월간 1 + 연간 1
		assertThat(summary.preservedGoals()).isZero();
		assertThat(summary.deletedActions()).isEqualTo(2);
		assertThat(exists("yearly_goals", yearly)).isFalse();
		assertThat(count("SELECT COUNT(*) FROM monthly_goals")).isZero();
		assertThat(count("SELECT COUNT(*) FROM weekly_goals")).isZero();
		assertThat(count("SELECT COUNT(*) FROM actions")).isZero();
		assertThat(count("SELECT COUNT(*) FROM action_quadrant_placements")).isZero();
		assertThat(count("SELECT COUNT(*) FROM goal_progress_logs")).isEqualTo(logs);
		assertThat(exists("yearly_goals", other)).isTrue();
	}

	@Test
	void yearlyRootKeepsPathsWithCompletedHistory() throws Exception {
		// w1: 완료 행동이 있어 보존 -> 월간 보존 -> 연간 보존. n1은 미완료만이라 사라진다.
		long done = api.goalAction(1L, w1, "완료함", "");
		api.expect(api.complete(1L, done, true), 200);
		long open = api.goalAction(1L, w1, "미완료", "");
		long openN = api.goalAction(1L, n1, "수치 미완료", ",\"contributionValue\":1");
		api.expect(delete(1L, "yearly", yearly, true), 200);
		setDeletedAt("yearly_goals", yearly, OLD);

		TrashPurgeService.Summary summary = purge.purgeExpired();

		assertThat(summary.deletedGoals()).isEqualTo(1); // n1
		assertThat(summary.preservedGoals()).isEqualTo(3); // w1 + 월간 + 연간
		assertThat(summary.deletedActions()).isEqualTo(2);
		assertThat(exists("yearly_goals", yearly)).isTrue();
		assertThat(isDeleted("yearly_goals", yearly)).isTrue();
		assertThat(exists("monthly_goals", monthly)).isTrue();
		assertThat(exists("weekly_goals", w1)).isTrue();
		assertThat(exists("weekly_goals", n1)).isFalse();
		assertThat(exists("actions", done)).isTrue();
		assertThat(exists("actions", open)).isFalse();
		assertThat(exists("actions", openN)).isFalse();
		// 보존된 과거 이력은 복원할 수 없다.
		api.expect(restore(1L, "yearly", yearly), 409, "$.code", "GOAL_RESTORE_EXPIRED");
		api.expect(api.get("/api/goals/trash", 1L), 200, "$.data.length()", 0);
	}

	@Test
	void yearlyWithAchievedCriterionIsKeptEvenWithoutChildren() throws Exception {
		long y2 = api.yearly(1L, "기준 있는 연간");
		jdbc.update("INSERT INTO yearly_goal_criteria (yearly_goal_id, criterion_text, weight, is_achieved) "
				+ "VALUES (?, '달성한 기준', 100, TRUE)", y2);
		long y3 = api.yearly(1L, "미달성 기준만");
		jdbc.update("INSERT INTO yearly_goal_criteria (yearly_goal_id, criterion_text, weight, is_achieved) "
				+ "VALUES (?, '미달성', 100, FALSE)", y3);
		setDeletedAt("yearly_goals", y2, OLD);
		setDeletedAt("yearly_goals", y3, OLD);

		purge.purgeExpired();

		assertThat(exists("yearly_goals", y2)).isTrue();
		assertThat(count("SELECT COUNT(*) FROM yearly_goal_criteria WHERE yearly_goal_id = ?", y2)).isEqualTo(1);
		// 달성한 기준이 없고 하위도 없으면 사라지고 기준 행은 CASCADE로 함께 지워진다.
		assertThat(exists("yearly_goals", y3)).isFalse();
		assertThat(count("SELECT COUNT(*) FROM yearly_goal_criteria WHERE yearly_goal_id = ?", y3)).isZero();
	}

	@Test
	void monthlyRootKeepsMonthWhenAWeeklyIsPreserved() throws Exception {
		long done = api.goalAction(1L, w1, "완료함", "");
		api.expect(api.complete(1L, done, true), 200);
		api.goalAction(1L, n1, "미완료", ",\"contributionValue\":1");
		api.expect(delete(1L, "monthly", monthly, true), 200);
		setDeletedAt("monthly_goals", monthly, OLD);

		purge.purgeExpired();

		assertThat(exists("monthly_goals", monthly)).isTrue();
		assertThat(exists("weekly_goals", w1)).isTrue();
		assertThat(exists("weekly_goals", n1)).isFalse();
		assertThat(exists("yearly_goals", yearly)).isTrue();
		assertThat(isDeleted("yearly_goals", yearly)).isFalse();
	}

	@Test
	void separatelyDeletedDescendantIsPurgedWithItsExpiredRoot() throws Exception {
		api.goalAction(1L, w1, "미완료", "");
		api.expect(delete(1L, "weekly", w1, true), 200);      // w1은 자신의 기한이 아직 남았다
		api.expect(delete(1L, "yearly", yearly, true), 200);
		setDeletedAt("yearly_goals", yearly, OLD);             // 연간만 기한 지남
		setDeletedAt("weekly_goals", w1, "2026-11-07T00:00:00Z");

		purge.purgeExpired();

		assertThat(exists("yearly_goals", yearly)).isFalse();
		assertThat(exists("weekly_goals", w1)).isFalse();
		assertThat(count("SELECT COUNT(*) FROM actions")).isZero();
	}

	@Test
	void notExpiredGoalsAreUntouched() throws Exception {
		api.goalAction(1L, w1, "미완료", "");
		api.expect(delete(1L, "yearly", yearly, true), 200);
		setDeletedAt("yearly_goals", yearly, "2026-10-14T00:00:00Z"); // 29일 전

		TrashPurgeService.Summary summary = purge.purgeExpired();

		assertThat(summary.roots()).isZero();
		assertThat(exists("yearly_goals", yearly)).isTrue();
		assertThat(count("SELECT COUNT(*) FROM actions")).isEqualTo(1);
		// 기한이 지나기 전에는 복원도 된다.
		api.expect(restore(1L, "yearly", yearly), 200);
	}

	@Test
	void expiredWeeklyRootUnderLiveParentsLeavesParentsAlone() throws Exception {
		api.goalAction(1L, w1, "미완료", "");
		setDeletedAt("weekly_goals", w1, OLD);

		purge.purgeExpired();

		assertThat(exists("weekly_goals", w1)).isFalse();
		assertThat(exists("weekly_goals", n1)).isTrue();
		assertThat(exists("monthly_goals", monthly)).isTrue();
		assertThat(exists("yearly_goals", yearly)).isTrue();
	}

	@Test
	void rerunningIsIdempotent() throws Exception {
		long done = api.goalAction(1L, w1, "완료함", "");
		api.expect(api.complete(1L, done, true), 200);
		api.goalAction(1L, n1, "미완료", ",\"contributionValue\":1");
		api.expect(delete(1L, "yearly", yearly, true), 200);
		setDeletedAt("yearly_goals", yearly, OLD);

		purge.purgeExpired();
		int goals = count("SELECT COUNT(*) FROM weekly_goals") + count("SELECT COUNT(*) FROM monthly_goals")
				+ count("SELECT COUNT(*) FROM yearly_goals");
		int actions = count("SELECT COUNT(*) FROM actions");

		// 보존된 root는 다음 회차에도 후보로 잡히지만 결과는 같다(삭제할 것이 더는 없다).
		TrashPurgeService.Summary second = purge.purgeExpired();

		assertThat(second.deletedGoals()).isZero();
		assertThat(second.deletedActions()).isZero();
		assertThat(count("SELECT COUNT(*) FROM weekly_goals") + count("SELECT COUNT(*) FROM monthly_goals")
				+ count("SELECT COUNT(*) FROM yearly_goals")).isEqualTo(goals);
		assertThat(count("SELECT COUNT(*) FROM actions")).isEqualTo(actions);
		assertThat(isDeleted("yearly_goals", yearly)).isTrue();
	}

	@Test
	void noRootsMeansNothingToDo() throws Exception {
		TrashPurgeService.Summary summary = purge.purgeExpired();
		assertThat(summary).isEqualTo(new TrashPurgeService.Summary(0, 0, 0, 0, 0, 0, 0));
	}

	@Test
	void purgeRootRechecksUnderLockAndSkipsRootsHandledAfterCandidateCollection() throws Exception {
		// 후보 수집(잠금 없음) 뒤에 다른 인스턴스가 먼저 처리한 상황: 하나는 복원, 하나는 이미 영구 삭제.
		long restored = w1;
		long purged = n1;
		api.goalAction(1L, restored, "미완료", "");
		setDeletedAt("weekly_goals", restored, OLD);
		setDeletedAt("weekly_goals", purged, OLD);

		java.util.List<TrashPurgeService.Root> candidates = purge.findExpiredRoots();
		assertThat(candidates).hasSize(2);

		jdbc.update("UPDATE weekly_goals SET deleted_at = NULL WHERE id = ?", restored);
		jdbc.update("DELETE FROM weekly_goals WHERE id = ?", purged);

		for (TrashPurgeService.Root root : candidates) {
			assertThat(purge.purgeRoot(root)).isEqualTo(TrashPurgeService.Outcome.SKIPPED);
		}
		assertThat(exists("weekly_goals", restored)).isTrue();
		assertThat(count("SELECT COUNT(*) FROM actions")).isEqualTo(1);
	}
}
