package com.aim2day.goal.reports.dto;

import com.aim2day.goal.reports.PeriodReview;

/** 직접 회고(API 명세 §4.12). 서버는 사용자가 쓴 내용을 그대로 돌려주며 AI 문장을 넣지 않는다. */
public record ReviewResponse(String improvements, String nextFocus, long version) {

	public static ReviewResponse of(PeriodReview r) {
		return new ReviewResponse(r.getImprovements(), r.getNextFocus(), r.getVersion());
	}
}
