package com.aim2day.goal.calendar.dto;

import com.aim2day.goal.calendar.CalendarItemType;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.time.LocalDate;

/**
 * 달력 항목 객체(API 명세 §4.11). yearlyGoal이 null이면 목표에 연결하지 않았거나 연결된 목표가 삭제 상태인 항목이다.
 * daysUntilDue는 TASK는 마감일, EVENT는 시작일까지 남은 일수다(지났으면 음수).
 */
public record CalendarItemResponse(long id, CalendarItemType itemType, String title, String description,
		LocalDate startDate, LocalDate endDate, @JsonProperty("isCompleted") boolean isCompleted, Instant completedAt,
		YearlyRef yearlyGoal, long daysUntilDue, long version) {

	public record YearlyRef(long id, String title, String color) {
	}
}
