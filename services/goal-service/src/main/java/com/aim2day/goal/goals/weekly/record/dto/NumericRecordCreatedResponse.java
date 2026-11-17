package com.aim2day.goal.goals.weekly.record.dto;

import com.aim2day.goal.goals.weekly.dto.WeeklyGoalResponse;

/** 기록을 추가한 뒤의 기록과 갱신된 주간 목표(진행률·현재값 포함). */
public record NumericRecordCreatedResponse(NumericRecordResponse record, WeeklyGoalResponse weeklyGoal) {
}
