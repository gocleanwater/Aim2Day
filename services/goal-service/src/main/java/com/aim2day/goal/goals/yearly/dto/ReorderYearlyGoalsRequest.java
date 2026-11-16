package com.aim2day.goal.goals.yearly.dto;

import java.util.List;

public record ReorderYearlyGoalsRequest(Integer year, List<Long> goalIds) {
}
