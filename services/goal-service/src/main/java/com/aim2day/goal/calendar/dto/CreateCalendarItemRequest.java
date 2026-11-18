package com.aim2day.goal.calendar.dto;

import com.aim2day.goal.calendar.CalendarItemType;
import java.time.LocalDate;

/** 달력 항목 생성 본문(API 명세 §4.11 POST). 종류별 조건부 규칙이 있어 검증은 서비스가 한다. */
public record CreateCalendarItemRequest(CalendarItemType itemType, String title, String description,
		LocalDate startDate, LocalDate endDate, Long yearlyGoalId) {
}
