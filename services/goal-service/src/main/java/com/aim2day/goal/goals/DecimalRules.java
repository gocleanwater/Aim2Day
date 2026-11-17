package com.aim2day.goal.goals;

import com.aim2day.goal.common.exception.FieldErrors;
import com.aim2day.goal.common.exception.ValidationReason;
import java.math.BigDecimal;

/** 수치 입력 검증(목표값·기록량·가중치 공통): 0 초과 최댓값 이하, 소수 둘째 자리까지. */
public final class DecimalRules {

	/** DECIMAL(10,2)의 상한. 목표값·기록량·현재값이 모두 이 범위다. */
	public static final BigDecimal MAX_VALUE = new BigDecimal("99999999.99");
	/** DECIMAL(5,2)의 상한. 가중치에 쓴다. */
	public static final BigDecimal MAX_WEIGHT = new BigDecimal("999.99");

	private DecimalRules() {
	}

	/**
	 * 통과하면 scale 2로 맞춘 값을 돌려주고, 아니면 errors에 담고 null을 돌려준다.
	 * 셋째 자리가 있으면 INVALID_FORMAT(10.50은 stripTrailingZeros로 통과), 범위를 벗어나면 OUT_OF_RANGE다.
	 * 1E+100 같은 값에 setScale을 하면 메모리를 크게 쓰므로, 정수 자릿수가 상한보다 크면 먼저 범위 오류로 끊는다.
	 */
	public static BigDecimal positive(BigDecimal raw, BigDecimal max, String field, FieldErrors errors) {
		if (raw == null) {
			errors.add(field, ValidationReason.REQUIRED);
			return null;
		}
		if (raw.precision() - raw.scale() > 12) {
			errors.add(field, ValidationReason.OUT_OF_RANGE);
			return null;
		}
		if (raw.stripTrailingZeros().scale() > 2) {
			errors.add(field, ValidationReason.INVALID_FORMAT);
			return null;
		}
		if (raw.signum() <= 0 || raw.compareTo(max) > 0) {
			errors.add(field, ValidationReason.OUT_OF_RANGE);
			return null;
		}
		return raw.setScale(2);
	}
}
