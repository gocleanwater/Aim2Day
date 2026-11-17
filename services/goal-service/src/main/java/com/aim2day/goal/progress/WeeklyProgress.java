package com.aim2day.goal.progress;

import java.math.BigDecimal;

/** 주간 진행률 재계산 결과. targetReached는 수치형만 값이 있다(current >= target, 반올림 전 값으로 비교). */
public record WeeklyProgress(long weeklyGoalId, BigDecimal rate, BigDecimal currentValue, Boolean targetReached) {
}
