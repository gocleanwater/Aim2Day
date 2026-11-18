package com.aim2day.goal.common.validation;

import com.aim2day.goal.common.exception.FieldErrors;
import com.aim2day.goal.common.exception.ValidationReason;

/**
 * 문자열 길이 검사. 길이는 UTF-16 char 수가 아니라 코드 포인트 수로 센다(이모지 등 보충 문자가 2로 세어지지 않게).
 * 도메인별 최대 길이는 호출부가 정한다. 오류는 errors에 쌓기만 하고, 끊는 시점(throwIfAny)은 호출자가 정한다.
 */
public final class TextRules {

	private TextRules() {
	}

	/** 앞뒤 공백을 지운 뒤 비어 있으면 REQUIRED, max 코드 포인트를 넘으면 TOO_LONG. 지운 문자열을 돌려준다(null은 빈 문자열로 본다). */
	public static String requiredStrippedText(String raw, String field, int max, FieldErrors errors) {
		String text = raw == null ? "" : raw.strip();
		if (text.isEmpty()) {
			errors.add(field, ValidationReason.REQUIRED);
		} else if (text.codePointCount(0, text.length()) > max) {
			errors.add(field, ValidationReason.TOO_LONG);
		}
		return text;
	}

	/** 공백을 지우지 않고 max 코드 포인트를 넘으면 TOO_LONG. null은 통과이며 raw를 그대로 돌려준다. */
	public static String maxLength(String raw, String field, int max, FieldErrors errors) {
		if (raw != null && raw.codePointCount(0, raw.length()) > max) {
			errors.add(field, ValidationReason.TOO_LONG);
		}
		return raw;
	}
}
