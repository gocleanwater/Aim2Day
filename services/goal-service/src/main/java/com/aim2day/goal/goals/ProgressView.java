package com.aim2day.goal.goals;

import java.math.BigDecimal;

/** 화면용 진행률(API 명세 §2.7). 계산할 하위 항목이 없으면 NO_RECORD이고 rate는 null이다. */
public record ProgressView(String state, BigDecimal rate) {

	public static final String RECORDED = "RECORDED";
	public static final String NO_RECORD = "NO_RECORD";

	/** 진행률이 계산된 상태(RECORDED)를 만든다. 0%도 기록이다. */
	public static ProgressView recorded(BigDecimal rate) {
		return new ProgressView(RECORDED, rate);
	}

	/** 계산할 하위 항목이 없는 상태(NO_RECORD)를 만든다. 화면은 0%가 아니라 "기록 없음"으로 보여 준다. */
	public static ProgressView noRecord() {
		return new ProgressView(NO_RECORD, null);
	}
}
