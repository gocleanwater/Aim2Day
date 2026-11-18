package com.aim2day.goal.onboarding.dto;

import java.util.List;
import java.util.Map;

/** 만든 항목의 tempId -> id 대응표. actions는 요청 순서의 id 목록이다. */
public record CompleteResponse(Map<String, Long> yearlyGoals, Map<String, Long> monthlyGoals,
		Map<String, Long> weeklyGoals, List<Long> actions) {
}
