package com.aim2day.goal.goals.yearly;

import com.aim2day.goal.common.exception.FieldErrors;
import com.aim2day.goal.common.exception.ValidationReason;
import java.util.Locale;
import java.util.regex.Pattern;

/** 연간 목표 입력 규칙(기능 명세와 계획 "이 단위에서 정한 것"). 생성과 수정이 같은 규칙을 쓴다. */
final class YearlyGoalRules {

	static final int TITLE_MAX = 100;
	/** 명세에 없는 값. 결과 요약(resultSummary)과 같은 상한을 쓴다. */
	static final int DESCRIPTION_MAX = 2000;
	static final String DEFAULT_COLOR = "#3B82F6";
	private static final Pattern COLOR = Pattern.compile("^#[0-9A-Fa-f]{6}$");

	private YearlyGoalRules() {
	}

	/** 앞뒤 공백을 지운 뒤 1~100자. 오류는 errors에 담고, 호출자가 마지막에 throwIfAny로 끊는다. */
	static String title(String raw, FieldErrors errors) {
		String title = raw == null ? "" : raw.strip();
		if (title.isEmpty()) {
			errors.add("title", ValidationReason.REQUIRED);
		} else if (title.codePointCount(0, title.length()) > TITLE_MAX) {
			errors.add("title", ValidationReason.TOO_LONG);
		}
		return title;
	}

	static String description(String raw, FieldErrors errors) {
		if (raw != null && raw.codePointCount(0, raw.length()) > DESCRIPTION_MAX) {
			errors.add("description", ValidationReason.TOO_LONG);
		}
		return raw;
	}

	/** #RRGGBB만 허용하고 저장은 대문자로 통일한다(같은 색이 대소문자만 달라 다른 값으로 보이지 않게). */
	static String color(String raw, FieldErrors errors) {
		if (raw == null || !COLOR.matcher(raw).matches()) {
			errors.add("color", raw == null ? ValidationReason.REQUIRED : ValidationReason.INVALID_FORMAT);
			return raw;
		}
		return raw.toUpperCase(Locale.ROOT);
	}
}
