package com.aim2day.goal.reports;

import com.aim2day.goal.common.exception.FieldErrors;
import com.aim2day.goal.common.exception.ValidationReason;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 리포트 기간 하나(유형, 식별값, 시작일, 끝일). 날짜는 모두 "계정 시간대의 달력 날짜"이므로 시각 변환이 없다.
 * 서버가 문자열로 받은 periodType·period를 한곳에서 파싱해, 리포트 조회와 회고 저장이 같은 형식 규칙을 쓰게 한다.
 */
public record ReportPeriod(PeriodType type, String value, LocalDate start, LocalDate end) {

	private static final Pattern MONTH = Pattern.compile("^(\\d{4})-(0[1-9]|1[0-2])$");
	private static final Pattern QUARTER = Pattern.compile("^(\\d{4})-Q([1-4])$");
	private static final Pattern YEAR = Pattern.compile("^(\\d{4})$");

	/**
	 * 형식이 틀리면 400 VALIDATION_FAILED(필드 periodType 또는 period, INVALID_FORMAT). 유형이 틀리면 식별값 형식은
	 * 판단할 수 없으므로 period 오류는 더하지 않는다. 미래 기간 검사(422)는 오늘이 필요하므로 호출자가 isFuture로 한다.
	 */
	public static ReportPeriod parse(String rawType, String rawPeriod) {
		FieldErrors errors = new FieldErrors();
		PeriodType type = null;
		try {
			type = rawType == null ? null : PeriodType.valueOf(rawType);
		} catch (IllegalArgumentException e) {
			// 알 수 없는 값은 아래에서 오류로 모은다.
		}
		if (type == null) {
			errors.add("periodType", ValidationReason.INVALID_FORMAT);
		}
		ReportPeriod period = type == null ? null : parseValue(type, rawPeriod);
		if (type != null && period == null) {
			errors.add("period", ValidationReason.INVALID_FORMAT);
		}
		errors.throwIfAny();
		return period;
	}

	private static ReportPeriod parseValue(PeriodType type, String raw) {
		if (raw == null) {
			return null;
		}
		switch (type) {
			case MONTHLY -> {
				Matcher m = MONTH.matcher(raw);
				if (!m.matches()) {
					return null;
				}
				return ofMonth(YearMonth.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))));
			}
			case QUARTERLY -> {
				Matcher m = QUARTER.matcher(raw);
				if (!m.matches()) {
					return null;
				}
				return ofQuarter(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)));
			}
			default -> {
				if (!YEAR.matcher(raw).matches()) {
					return null;
				}
				return ofYear(Integer.parseInt(raw));
			}
		}
	}

	public static ReportPeriod ofMonth(YearMonth ym) {
		return new ReportPeriod(PeriodType.MONTHLY, ym.toString(), ym.atDay(1), ym.atEndOfMonth());
	}

	public static ReportPeriod ofQuarter(int year, int quarter) {
		LocalDate start = LocalDate.of(year, (quarter - 1) * 3 + 1, 1);
		return new ReportPeriod(PeriodType.QUARTERLY, year + "-Q" + quarter, start, start.plusMonths(3).minusDays(1));
	}

	public static ReportPeriod ofYear(int year) {
		return new ReportPeriod(PeriodType.YEARLY, String.format("%04d", year), LocalDate.of(year, 1, 1),
				LocalDate.of(year, 12, 31));
	}

	/** 시작일이 오늘 이후면 아직 시작하지 않은 기간이다(오늘이 시작일이면 진행 중이라 조회할 수 있다). */
	public boolean isFuture(LocalDate today) {
		return start.isAfter(today);
	}

	/** 기간이 속한 연도. 월·분기·연간 모두 한 해 안에 있으므로 시작일의 연도다. */
	public int year() {
		return start.getYear();
	}

	/** 진행 중이면(끝일이 오늘 이후) 오늘까지만 계산하므로 실질 끝은 오늘이다. */
	public LocalDate effectiveEnd(LocalDate today) {
		return end.isAfter(today) ? today : end;
	}

	/** 탭 라벨: {@code 2026.12 월간}, {@code 2026 4분기}, {@code 2026년 연간}. */
	public String tabLabel() {
		return switch (type) {
			case MONTHLY -> String.format("%d.%02d 월간", start.getYear(), start.getMonthValue());
			case QUARTERLY -> String.format("%d %d분기", start.getYear(), quarterOf(start));
			case YEARLY -> String.format("%d년 연간", start.getYear());
		};
	}

	/** 이번 기간 이름: {@code 11월 말}, {@code 4분기 말}, {@code 2026년 말}. 막대 아래 endLabel에 쓴다. */
	public String endLabel() {
		return switch (type) {
			case MONTHLY -> start.getMonthValue() + "월 말";
			case QUARTERLY -> quarterOf(start) + "분기 말";
			case YEARLY -> start.getYear() + "년 말";
		};
	}

	/** 지난 기간 이름: 1월이면 {@code 12월 말}, 1분기면 {@code 4분기 말}(해가 바뀌어도 이름만 쓴다), 연간은 전년. */
	public String previousEndLabel() {
		return switch (type) {
			case MONTHLY -> start.minusMonths(1).getMonthValue() + "월 말";
			case QUARTERLY -> quarterOf(start.minusMonths(3)) + "분기 말";
			case YEARLY -> (start.getYear() - 1) + "년 말";
		};
	}

	private static int quarterOf(LocalDate date) {
		return (date.getMonthValue() - 1) / 3 + 1;
	}
}
