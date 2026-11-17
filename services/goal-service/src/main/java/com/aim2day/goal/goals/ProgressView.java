package com.aim2day.goal.goals;

import java.math.BigDecimal;

/** 화면용 진행률(API 명세 §2.7). 계산할 하위 항목이 없으면 NO_RECORD이고 rate는 null이다. */
public record ProgressView(String state, BigDecimal rate) {

	public static final String RECORDED = "RECORDED";
	public static final String NO_RECORD = "NO_RECORD";

	public static ProgressView recorded(BigDecimal rate) {
		return new ProgressView(RECORDED, rate);
	}

	public static ProgressView noRecord() {
		return new ProgressView(NO_RECORD, null);
	}
}
