package com.aim2day.goal.goals.weekly.record.dto;

import com.aim2day.goal.goals.weekly.dto.WeeklyGoalResponse;

/** 명세에 응답 본문이 없어 추가 응답과 같은 키(weeklyGoal)로 차감·재계산된 주간 목표를 돌려준다. */
public record NumericRecordDeletedResponse(WeeklyGoalResponse weeklyGoal) {
}
