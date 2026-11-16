package com.aim2day.goal.goals.criteria.dto;

import com.aim2day.goal.common.time.UtcTime;
import com.aim2day.goal.goals.criteria.CriteriaCalculator;
import com.aim2day.goal.goals.criteria.CriteriaSummary;
import com.aim2day.goal.goals.criteria.YearlyGoalCriterion;
import com.aim2day.goal.goals.yearly.YearlyGoal;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;

/** 달성 기준 조회·저장·확정 응답(API 명세 §4.4). 세 API가 같은 형식을 쓴다. */
public record CriteriaResponse(String state, Integer achievementRate, Integer initialBaselineRate, int totalWeight,
		Instant confirmedAt, Instant lastRevisedAt, long goalVersion, List<Item> items) {

	public record Item(long id, String text, int weight, @JsonProperty("isAchieved") boolean isAchieved,
			Instant achievedAt, @JsonProperty("isBaselineAchieved") boolean isBaselineAchieved) {
	}

	public static CriteriaResponse of(YearlyGoal goal, List<YearlyGoalCriterion> criteria) {
		CriteriaSummary summary = CriteriaCalculator.summary(goal, criteria);
		List<Item> items = criteria.stream()
				.map(c -> new Item(c.getId(), c.getText(), c.getWeight(), c.isAchieved(),
						UtcTime.toInstant(c.getAchievedAt()), c.isBaselineAchieved()))
				.toList();
		return new CriteriaResponse(summary.state(), summary.achievementRate(), summary.initialBaselineRate(),
				CriteriaCalculator.totalWeight(criteria), summary.confirmedAt(), summary.lastRevisedAt(),
				goal.getVersion(), items);
	}
}
