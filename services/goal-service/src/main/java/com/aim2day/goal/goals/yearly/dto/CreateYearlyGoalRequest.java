package com.aim2day.goal.goals.yearly.dto;

/** 필드 검증은 서비스가 한다(제목은 공백을 지운 뒤 길이를 재야 하고, 연도 범위는 계정 시간대 기준이라서). */
public record CreateYearlyGoalRequest(Integer targetYear, String title, String description, String color) {
}
