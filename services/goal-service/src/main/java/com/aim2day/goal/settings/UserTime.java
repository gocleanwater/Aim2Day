package com.aim2day.goal.settings;

import com.aim2day.goal.common.time.CalendarMath;
import com.aim2day.goal.common.time.DateRange;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;

/**
 * 사용자 설정(시간대·주 시작 요일)으로 계산한 "지금". 이후 모든 서비스가 오늘·이번 주·이번 달을
 * 직접 계산하지 않고 SettingsService.userTime()으로 이 값을 받아 쓴다.
 */
public record UserTime(ZoneId zone, DayOfWeek weekStart, LocalDate today, DateRange currentWeek,
		YearMonth currentMonth) {

	public DateRange currentMonthRange() {
		return CalendarMath.monthOf(currentMonth);
	}
}
