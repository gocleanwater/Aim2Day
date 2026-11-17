package com.aim2day.goal.progress;

import java.math.BigDecimal;

/** 월간 진행률 재계산 결과. weeklyCount가 0이면 화면은 기록 없음을 보여 준다. */
public record MonthlyProgress(long monthlyGoalId, BigDecimal rate, int weeklyCount) {
}
