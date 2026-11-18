package com.aim2day.goal.settings.dto;

import com.aim2day.goal.common.time.DateRange;
import com.aim2day.goal.settings.UserSetting;
import com.aim2day.goal.settings.UserTime;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;

/** 이 도메인의 요청·응답 형식 모음. */
public final class SettingsDtos {

	private SettingsDtos() {
	}

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

	/** weekStartDay에 없는 요일이 오면 JSON 역직렬화에서 실패해 400 MALFORMED_REQUEST가 된다. */
	public record UpdateSettingsRequest(
			@NotNull DayOfWeek weekStartDay,
			@NotBlank String timezone,
			@NotNull Long version) {
	}
}
