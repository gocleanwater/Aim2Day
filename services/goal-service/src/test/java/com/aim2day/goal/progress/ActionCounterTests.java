package com.aim2day.goal.progress;

import static org.assertj.core.api.Assertions.assertThat;

import com.aim2day.goal.support.GoalTestSupport;
import com.aim2day.goal.support.MutableClock;
import com.aim2day.goal.support.TestClockConfig;
import com.aim2day.goal.support.TestSessionConfig;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/** 체크형 집계 규칙: 완료(삭제 포함) / (미삭제 + 삭제된 완료). */
@SpringBootTest
@Import({TestSessionConfig.class, TestClockConfig.class})
class ActionCounterTests {

	private static final LocalDate DAY = LocalDate.of(2026, 11, 12);

	@Autowired
	WeeklyActionCounter counter;
	@Autowired
	JdbcTemplate jdbc;
	@Autowired
	MutableClock clock;

	long weekly;

	@BeforeEach
	void seed() {
		clock.reset();
		GoalTestSupport.clean(jdbc);
		long yearly = GoalTestSupport.seedYearly(jdbc, 1L, 2026, "연간");
		long monthly = GoalTestSupport.seedMonthly(jdbc, yearly, LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30),
				"월간");
		weekly = GoalTestSupport.seedWeekly(jdbc, monthly, LocalDate.of(2026, 11, 8), LocalDate.of(2026, 11, 14),
				"주간", "CHECKLIST", null);
	}

	private long action(boolean completed, boolean deleted) {
		return GoalTestSupport.seedAction(jdbc, 1L, weekly, "행동", DAY, completed, deleted);
	}

	@Test
	void twoCompletedOneIncompleteIsTwoOfThree() {
		action(true, false);
		action(true, false);
		action(false, false);
		assertThat(counter.counts(weekly)).isEqualTo(new ActionCounts(2, 3));
		assertThat(ProgressMath.weeklyChecklist(2, 3)).isEqualByComparingTo("66.67");
	}

	@Test
	void deletedIncompleteLeavesTheDenominator() {
		action(true, false);
		action(true, false);
		action(false, true);
		assertThat(counter.counts(weekly)).isEqualTo(new ActionCounts(2, 2));
		assertThat(ProgressMath.weeklyChecklist(2, 2)).isEqualByComparingTo("100.00");
	}

	@Test
	void deletedCompletedKeepsNumeratorAndDenominator() {
		action(true, true);
		action(true, false);
		action(false, false);
		assertThat(counter.counts(weekly)).isEqualTo(new ActionCounts(2, 3));
	}

	@Test
	void batchCountsMatchSingleCounts() {
		action(true, false);
		action(true, true);
		action(false, false);
		action(false, true);
		long other = GoalTestSupport.seedWeekly(jdbc,
				jdbc.queryForObject("SELECT monthly_goal_id FROM weekly_goals WHERE id = ?", Long.class, weekly),
				LocalDate.of(2026, 11, 8), LocalDate.of(2026, 11, 14), "다른 주간", "CHECKLIST", null);
		GoalTestSupport.seedAction(jdbc, 1L, other, "다른 행동", DAY, false, false);
		long empty = GoalTestSupport.seedWeekly(jdbc,
				jdbc.queryForObject("SELECT monthly_goal_id FROM weekly_goals WHERE id = ?", Long.class, weekly),
				LocalDate.of(2026, 11, 8), LocalDate.of(2026, 11, 14), "빈 주간", "CHECKLIST", null);

		var batch = counter.countsFor(java.util.List.of(weekly, other, empty));
		assertThat(batch).containsOnlyKeys(weekly, other, empty);
		assertThat(batch.get(weekly)).isEqualTo(counter.counts(weekly)).isEqualTo(new ActionCounts(2, 3));
		assertThat(batch.get(other)).isEqualTo(counter.counts(other)).isEqualTo(new ActionCounts(0, 1));
		assertThat(batch.get(empty)).isEqualTo(new ActionCounts(0, 0));
		assertThat(counter.countsFor(java.util.List.of())).isEmpty();
	}

	@Test
	void otherWeeklyGoalsAreNotCounted() {
		action(true, false);
		long other = GoalTestSupport.seedWeekly(jdbc,
				jdbc.queryForObject("SELECT monthly_goal_id FROM weekly_goals WHERE id = ?", Long.class, weekly),
				LocalDate.of(2026, 11, 8), LocalDate.of(2026, 11, 14), "다른 주간", "CHECKLIST", null);
		GoalTestSupport.seedAction(jdbc, 1L, other, "다른 행동", DAY, false, false);
		assertThat(counter.counts(weekly)).isEqualTo(new ActionCounts(1, 1));
		assertThat(counter.counts(other)).isEqualTo(new ActionCounts(0, 1));
	}
}
