package com.aim2day.goal.goals;

/** 주간 목표 응답의 path에 담는 월간 목표 요약. month는 YYYY-MM이다. */
public record MonthlyRef(long id, String title, String month) {
}
