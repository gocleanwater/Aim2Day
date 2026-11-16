package com.aim2day.goal.goals.criteria.dto;

import java.util.List;

/** baselineAchievedCriterionIds를 생략하거나 비우면 기준선은 0%다. */
public record ConfirmCriteriaRequest(Long goalVersion, List<Long> baselineAchievedCriterionIds) {
}
