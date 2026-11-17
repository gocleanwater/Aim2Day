package com.aim2day.goal.goals.monthly.dto;

/** 대상 월은 받지 않는다. 서버가 계정 시간대 기준 현재 달로 만든다(GOAL-014). color가 null이면 부모 색을 상속한다. */
public record CreateMonthlyGoalRequest(Long yearlyGoalId, String title, String description, String color) {
}
