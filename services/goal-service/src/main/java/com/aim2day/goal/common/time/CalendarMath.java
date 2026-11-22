package com.aim2day.goal.common.time;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;

/**
 * "오늘·이번 주·이번 달" 계산. 상태가 없는 순수 함수라 인스턴스가 달라도 같은 입력이면 같은 결과다.
 * 서버(UTC)가 아니라 사용자 계정 시간대 기준이어야 한다(API 명세 §2.3).
 */
public final class CalendarMath {

	private CalendarMath() {
	}

	/** 같은 순간도 시간대에 따라 날짜가 다르다(UTC 15:30은 서울에서 다음 날). */
	public static LocalDate today(Clock clock, ZoneId zone) {
		return LocalDate.now(clock.withZone(zone));
	}

	/** date가 속한 주. 시작일은 date 이전이거나 같은 가장 가까운 weekStart 요일이고, 끝은 6일 뒤다. */
	public static DateRange weekContaining(LocalDate date, DayOfWeek weekStart) {
		LocalDate start = date.with(TemporalAdjusters.previousOrSame(weekStart));
		return new DateRange(start, start.plusDays(6));
	}

	/** 월의 첫날과 마지막 날을 기간으로 돌려준다. */
	public static DateRange monthOf(YearMonth month) {
		return new DateRange(month.atDay(1), month.atEndOfMonth());
	}
}
