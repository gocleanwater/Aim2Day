package com.aim2day.goal.goals.criteria;

import static org.assertj.core.api.Assertions.assertThat;

import com.aim2day.goal.goals.yearly.YearlyGoal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class CriteriaCalculatorTests {

	private static YearlyGoalCriterion criterion(int weight, boolean achieved) {
		YearlyGoalCriterion c = new YearlyGoalCriterion(1L, "기준", weight);
		if (achieved) {
			c.achieve(LocalDateTime.of(2026, 11, 12, 3, 0));
		}
		return c;
	}

	@Test
	void sumsWeightsOfAchievedCriteriaOnly() {
		List<YearlyGoalCriterion> criteria = List.of(criterion(40, false), criterion(10, true), criterion(50, true));
		assertThat(CriteriaCalculator.achievementRate(criteria)).isEqualTo(60);
		assertThat(CriteriaCalculator.totalWeight(criteria)).isEqualTo(100);
	}

	@Test
	void emptyListIsZero() {
		assertThat(CriteriaCalculator.achievementRate(List.of())).isZero();
	}

	@Test
	void unconfirmedGoalHasNoPercent() {
		YearlyGoal goal = new YearlyGoal(1L, 2026, "목표", null, "#3B82F6", 0);
		CriteriaSummary summary = CriteriaCalculator.summary(goal, List.of(criterion(100, true)));
		assertThat(summary.state()).isEqualTo("NOT_CONFIGURED");
		assertThat(summary.achievementRate()).isNull();
		assertThat(summary.initialBaselineRate()).isNull();
		assertThat(summary.confirmedAt()).isNull();
		assertThat(summary.lastRevisedAt()).isNull();
	}
}
