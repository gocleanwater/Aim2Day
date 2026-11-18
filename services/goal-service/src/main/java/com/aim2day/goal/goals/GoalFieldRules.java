package com.aim2day.goal.goals;

import com.aim2day.goal.common.exception.FieldErrors;
import com.aim2day.goal.common.exception.ValidationReason;
import com.aim2day.goal.common.validation.TextRules;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/** 연간·월간·주간 목표 공통 입력 규칙(기능 명세와 계획 "이 단위에서 정한 것"). 생성과 수정이 같은 규칙을 쓴다. */
public final class GoalFieldRules {

	public static final int TITLE_MAX = 100;
	/** 명세에 없는 값. 결과 요약(resultSummary)과 같은 상한을 쓴다. */
	public static final int DESCRIPTION_MAX = 2000;
	public static final String DEFAULT_COLOR = "#3B82F6";
	private static final Pattern COLOR = Pattern.compile("^#[0-9A-Fa-f]{6}$");

	private GoalFieldRules() {
	}

	/** 앞뒤 공백을 지운 뒤 1~100자. 오류는 errors에 담고, 호출자가 마지막에 throwIfAny로 끊는다. */
	public static String title(String raw, FieldErrors errors) {
		return TextRules.requiredStrippedText(raw, "title", TITLE_MAX, errors);
	}

	public static String description(String raw, FieldErrors errors) {
		return TextRules.maxLength(raw, "description", DESCRIPTION_MAX, errors);
	}

	/** #RRGGBB만 허용하고 저장은 대문자로 통일한다(같은 색이 대소문자만 달라 다른 값으로 보이지 않게). */
	public static String color(String raw, FieldErrors errors) {
		if (raw == null || !COLOR.matcher(raw).matches()) {
			errors.add("color", raw == null ? ValidationReason.REQUIRED : ValidationReason.INVALID_FORMAT);
			return raw;
		}
		return raw.toUpperCase(Locale.ROOT);
	}

	// ---- 수정(PATCH)용 필드별 함수. 값이 없으면(patch == null) 현재 값을 유지하고(생략), JSON null(Optional.empty())은
	// 필드마다 다르게 다룬다. 엔티티 변경·조회·잠금은 호출 서비스의 몫이며, 이 함수들은 오류만 errors에 쌓고
	// throwIfAny는 하지 않는다(주간 목표는 공통 필드 뒤에 추가 검증이 이어져 오류 목록의 순서가 유지돼야 한다).

	/** 생략이면 current 유지, JSON null이면 REQUIRED(제목은 지울 수 없다), 값이 있으면 title 규칙으로 검사한다. */
	public static String patchTitle(Optional<String> patch, String current, FieldErrors errors) {
		if (patch == null) {
			return current;
		}
		if (patch.isPresent()) {
			return title(patch.get(), errors);
		}
		errors.add("title", ValidationReason.REQUIRED);
		return current;
	}

	/** 생략이면 current 유지, JSON null이면 설명을 지운다(null), 값이 있으면 description 규칙으로 검사한다. */
	public static String patchDescription(Optional<String> patch, String current, FieldErrors errors) {
		if (patch == null) {
			return current;
		}
		return patch.isPresent() ? description(patch.get(), errors) : null;
	}

	/** 연간 목표용. 생략이면 유지, JSON null이면 REQUIRED(연간 색은 지울 수 없다), 값이 있으면 color 규칙으로 검사한다. */
	public static String patchRequiredColor(Optional<String> patch, String current, FieldErrors errors) {
		if (patch == null) {
			return current;
		}
		if (patch.isPresent()) {
			return color(patch.get(), errors);
		}
		errors.add("color", ValidationReason.REQUIRED);
		return current;
	}

	/** 월간·주간 목표용. 생략이면 유지, JSON null이면 null("부모 색 상속으로 되돌림"), 값이 있으면 color 규칙으로 검사한다. */
	public static String patchInheritableColor(Optional<String> patch, String current, FieldErrors errors) {
		if (patch == null) {
			return current;
		}
		return patch.isPresent() ? color(patch.get(), errors) : null;
	}

	/** 생략이면 current 유지, JSON null이면 REQUIRED(상태는 지울 수 없다), 값이 있으면 그 값이다. */
	public static GoalStatus patchStatus(Optional<GoalStatus> patch, GoalStatus current, FieldErrors errors) {
		if (patch == null) {
			return current;
		}
		if (patch.isPresent()) {
			return patch.get();
		}
		errors.add("status", ValidationReason.REQUIRED);
		return current;
	}
}
