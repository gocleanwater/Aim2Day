package com.aim2day.goal.goals.criteria.dto;

import com.aim2day.goal.common.time.UtcTime;
import com.aim2day.goal.goals.criteria.CriteriaCalculator;
import com.aim2day.goal.goals.criteria.CriteriaSummary;
import com.aim2day.goal.goals.criteria.YearlyGoalCriterion;
import com.aim2day.goal.goals.yearly.YearlyGoal;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

/** 이 도메인의 요청·응답 형식 모음. */
public final class CriteriaDtos {

	private CriteriaDtos() {
	}

	/** 목표 상태를 지정하는 요청이라 version을 받지 않는다(API 명세 §2.4). */
	public record AchievementRequest(@NotNull Boolean achieved, @Size(max = 255) String memo) {
	}

	public record AchievementResponse(long criterionId, @JsonProperty("isAchieved") boolean isAchieved,
			int achievementRate) {
	}

	/** baselineAchievedCriterionIds를 생략하거나 비우면 기준선은 0%다. */
	public record ConfirmCriteriaRequest(Long goalVersion, List<Long> baselineAchievedCriterionIds) {
	}

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

	/** 검증은 서비스가 한다. 항목별 오류 위치(items[i].text)를 알려야 하고, 기존 기준 id와도 대조해야 하기 때문이다. */
	public record SaveCriteriaRequest(Long goalVersion, List<Item> items, String memo) {

		public record Item(Long id, String text, Integer weight) {
		}
	}
}
