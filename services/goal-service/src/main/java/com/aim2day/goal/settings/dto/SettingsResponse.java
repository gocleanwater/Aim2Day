package com.aim2day.goal.settings.dto;

import com.aim2day.goal.common.time.DateRange;
import com.aim2day.goal.settings.UserSetting;
import com.aim2day.goal.settings.UserTime;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;

public record SettingsResponse(DayOfWeek weekStartDay, String timezone, long version, LocalDate today,
		Range currentWeek, MonthRange currentMonth) {

	public record Range(LocalDate startDate, LocalDate endDate) {
	}

	public record MonthRange(YearMonth month, LocalDate startDate, LocalDate endDate) {
	}

	public static SettingsResponse of(UserSetting setting, UserTime time) {
		DateRange week = time.currentWeek();
		DateRange month = time.currentMonthRange();
		return new SettingsResponse(setting.getWeekStartDay(), setting.getTimezone(), setting.getVersion(),
				time.today(), new Range(week.startDate(), week.endDate()),
				new MonthRange(time.currentMonth(), month.startDate(), month.endDate()));
	}
}
