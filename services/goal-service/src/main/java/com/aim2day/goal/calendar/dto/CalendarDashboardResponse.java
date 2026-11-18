package com.aim2day.goal.calendar.dto;

import com.aim2day.goal.calendar.CalendarItemType;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** 대시보드의 해야 할 일·일정 두 열(API 명세 §4.11). today는 계정 시간대 기준이다. */
public record CalendarDashboardResponse(LocalDate today, List<CalendarItemResponse> tasks,
		List<CalendarItemResponse> events, List<PastEvent> pastEvents) {

	/** 달력 항목 객체에 daysSinceEnd(오늘 - 종료일)를 더한 지난 일정. */
	public record PastEvent(long id, CalendarItemType itemType, String title, String description, LocalDate startDate,
			LocalDate endDate, @JsonProperty("isCompleted") boolean isCompleted, Instant completedAt,
			CalendarItemResponse.YearlyRef yearlyGoal, long daysUntilDue, long version, long daysSinceEnd) {

		public static PastEvent of(CalendarItemResponse item, long daysSinceEnd) {
			return new PastEvent(item.id(), item.itemType(), item.title(), item.description(), item.startDate(),
					item.endDate(), item.isCompleted(), item.completedAt(), item.yearlyGoal(), item.daysUntilDue(),
					item.version(), daysSinceEnd);
		}
	}
}
