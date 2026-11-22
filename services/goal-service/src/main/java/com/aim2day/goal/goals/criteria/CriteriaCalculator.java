package com.aim2day.goal.goals.criteria;

import com.aim2day.goal.common.time.UtcTime;
import com.aim2day.goal.goals.yearly.YearlyGoal;
import java.util.List;

/**
 * 연간 달성률 계산. 연간 목표에는 달성률을 저장하지 않고 기준 행에서 매번 계산한다(ERD 결정 17).
 * 저장하면 기준 저장·달성이 동시에 올 때 저장값과 기준 행이 어긋날 수 있다.
 */
public final class CriteriaCalculator {

	private CriteriaCalculator() {
	}

	/** 연간 달성률 = 달성한 기준의 배점 합(정수, API 명세 §2.3). 기준이 없으면 0. */
	public static int achievementRate(List<YearlyGoalCriterion> criteria) {
		int sum = 0;
		for (YearlyGoalCriterion criterion : criteria) {
			if (criterion.isAchieved()) {
				sum += criterion.getWeight();
			}
		}
		return sum;
	}

	/** 달성 기준 배점의 합계를 구한다. 확정 조건(100)과 달성률 계산에 쓴다. */
	public static int totalWeight(List<YearlyGoalCriterion> criteria) {
		int sum = 0;
		for (YearlyGoalCriterion criterion : criteria) {
			sum += criterion.getWeight();
		}
		return sum;
	}

	/** 목표 응답에 담는 기준 요약. 확정 전에는 퍼센트를 노출하지 않는다(GOAL-006). */
	public static CriteriaSummary summary(YearlyGoal goal, List<YearlyGoalCriterion> criteria) {
		if (!goal.isCriteriaConfirmed()) {
			return new CriteriaSummary(CriteriaSummary.NOT_CONFIGURED, null, null, null, null);
		}
		return new CriteriaSummary(CriteriaSummary.CONFIRMED, achievementRate(criteria),
				goal.getInitialBaselineRate(), UtcTime.toInstant(goal.getCriteriaConfirmedAt()),
				UtcTime.toInstant(goal.getCriteriaLastRevisedAt()));
	}
}
