package com.aim2day.goal.progress;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * 진행률 계산식(ERD §4.3). 상태가 없는 순수 함수다. 모든 결과는 소수 둘째 자리 HALF_UP이다.
 * 나눗셈은 곱셈을 먼저 하고 마지막에 한 번만 반올림한다. 중간에 반올림하면 33.33 같은 값이 쌓여 오차가 생긴다.
 */
public final class ProgressMath {

	private static final BigDecimal HUNDRED = new BigDecimal("100");
	private static final BigDecimal ZERO = new BigDecimal("0.00");
	private static final BigDecimal FULL = new BigDecimal("100.00");

	private ProgressMath() {
	}

	/** 체크형: 완료 행동 수 / 분모 x 100. 분모가 0이면 0.00이다(화면의 "기록 없음"은 호출자가 분모로 판단한다). */
	public static BigDecimal weeklyChecklist(int completed, int total) {
		if (total <= 0) {
			return ZERO;
		}
		BigDecimal rate = BigDecimal.valueOf(completed).multiply(HUNDRED)
				.divide(BigDecimal.valueOf(total), 2, RoundingMode.HALF_UP);
		return rate.min(FULL);
	}

	/** 수치형: MIN(100, ROUND(current / target x 100, 2)). 음수는 0이다. 초과 달성값은 current_value에만 남는다. */
	public static BigDecimal weeklyNumeric(BigDecimal current, BigDecimal target) {
		if (target == null || target.signum() <= 0 || current.signum() <= 0) {
			return ZERO;
		}
		BigDecimal rate = current.multiply(HUNDRED).divide(target, 2, RoundingMode.HALF_UP);
		return rate.min(FULL);
	}

	/**
	 * 월간: 하위 주간 진행률의 가중 평균. 이미 반올림되어 저장된 주간 값을 쓰고(ERD §4.3), 하위가 없으면 0.00이다.
	 * 기록 없는 주간(0%)도 분모에 들어간다.
	 */
	public static BigDecimal monthly(List<WeightedRate> weeklies) {
		BigDecimal weightedSum = BigDecimal.ZERO;
		BigDecimal weightSum = BigDecimal.ZERO;
		for (WeightedRate w : weeklies) {
			weightedSum = weightedSum.add(w.rate().multiply(w.weight()));
			weightSum = weightSum.add(w.weight());
		}
		if (weightSum.signum() <= 0) {
			return ZERO;
		}
		return weightedSum.divide(weightSum, 2, RoundingMode.HALF_UP);
	}
}
