package com.aim2day.goal.common.time;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class CalendarMathTests {

	private static DateRange range(String start, String end) {
		return new DateRange(LocalDate.parse(start), LocalDate.parse(end));
	}

	@Test
	void sundayStartWeek() {
		assertThat(CalendarMath.weekContaining(LocalDate.parse("2026-11-12"), DayOfWeek.SUNDAY))
				.isEqualTo(range("2026-11-08", "2026-11-14"));
	}

	@Test
	void mondayStartWeek() {
		assertThat(CalendarMath.weekContaining(LocalDate.parse("2026-11-12"), DayOfWeek.MONDAY))
				.isEqualTo(range("2026-11-09", "2026-11-15"));
	}

	@Test
	void dateOnWeekStartDayStartsThatDay() {
		assertThat(CalendarMath.weekContaining(LocalDate.parse("2026-11-08"), DayOfWeek.SUNDAY))
				.isEqualTo(range("2026-11-08", "2026-11-14"));
	}

	@Test
	void weekCrossesYearEnd() {
		assertThat(CalendarMath.weekContaining(LocalDate.parse("2026-12-31"), DayOfWeek.SUNDAY))
				.isEqualTo(range("2026-12-27", "2027-01-02"));
	}

	@Test
	void todayUsesAccountTimezone() {
		Clock clock = Clock.fixed(Instant.parse("2026-11-12T15:30:00Z"), ZoneOffset.UTC);
		assertThat(CalendarMath.today(clock, ZoneId.of("Asia/Seoul"))).isEqualTo(LocalDate.parse("2026-11-13"));
		assertThat(CalendarMath.today(clock, ZoneId.of("UTC"))).isEqualTo(LocalDate.parse("2026-11-12"));
	}

	@Test
	void februaryLeapYear() {
		assertThat(CalendarMath.monthOf(YearMonth.of(2028, 2))).isEqualTo(range("2028-02-01", "2028-02-29"));
	}
}
