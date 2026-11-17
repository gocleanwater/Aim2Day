package com.aim2day.goal.progress;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProgressMathTests {

	private static BigDecimal d(String value) {
		return new BigDecimal(value);
	}

	@Test
	void checklistRoundsHalfUpToTwoDecimals() {
		assertThat(ProgressMath.weeklyChecklist(2, 3)).isEqualByComparingTo("66.67");
		assertThat(ProgressMath.weeklyChecklist(1, 3)).isEqualByComparingTo("33.33");
		assertThat(ProgressMath.weeklyChecklist(3, 3)).isEqualByComparingTo("100.00");
	}

	@Test
	void checklistWithNoDenominatorIsZero() {
		assertThat(ProgressMath.weeklyChecklist(0, 0)).isEqualByComparingTo("0.00");
		assertThat(ProgressMath.weeklyChecklist(0, 0).scale()).isEqualTo(2);
	}

	@Test
	void numericIsCappedAtHundred() {
		assertThat(ProgressMath.weeklyNumeric(d("14.00"), d("10.00"))).isEqualByComparingTo("100.00");
	}

	@Test
	void numericNearTargetRoundsUpToHundred() {
		// 9999.99 / 10000 = 99.9999% 는 반올림하면 100.00이다. 완료 여부는 이 값이 아니라 원래 값 비교로 정한다.
		assertThat(ProgressMath.weeklyNumeric(d("9999.99"), d("10000.00"))).isEqualByComparingTo("100.00");
	}

	@Test
	void numericNegativeOrZeroIsZero() {
		assertThat(ProgressMath.weeklyNumeric(d("-1.00"), d("10.00"))).isEqualByComparingTo("0.00");
		assertThat(ProgressMath.weeklyNumeric(d("0.00"), d("10.00"))).isEqualByComparingTo("0.00");
	}

	@Test
	void numericPartialProgress() {
		assertThat(ProgressMath.weeklyNumeric(d("3.00"), d("10.00"))).isEqualByComparingTo("30.00");
		assertThat(ProgressMath.weeklyNumeric(d("1.00"), d("3.00"))).isEqualByComparingTo("33.33");
	}

	@Test
	void monthlyIsWeightedAverageIncludingZeroPercentWeeks() {
		BigDecimal rate = ProgressMath.monthly(List.of(new WeightedRate(d("100"), d("1")),
				new WeightedRate(d("50"), d("1")), new WeightedRate(d("0"), d("2"))));
		assertThat(rate).isEqualByComparingTo("37.50");
	}

	@Test
	void monthlyRoundsHalfUpOnAlreadyRoundedWeeklyValues() {
		BigDecimal rate = ProgressMath.monthly(
				List.of(new WeightedRate(d("66.67"), d("1")), new WeightedRate(d("33.33"), d("2"))));
		assertThat(rate).isEqualByComparingTo("44.44");
	}

	@Test
	void monthlyWithNoWeeklyGoalsIsZero() {
		assertThat(ProgressMath.monthly(List.of())).isEqualByComparingTo("0.00");
	}
}
