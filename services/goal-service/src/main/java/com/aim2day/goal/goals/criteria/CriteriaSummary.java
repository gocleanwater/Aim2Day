package com.aim2day.goal.goals.criteria;

import java.time.Instant;

/**
 * 연간 목표 응답의 criteria 객체(API 명세 §4.3). 확정 전에는 state만 NOT_CONFIGURED이고 나머지는 null이라
 * 화면이 퍼센트 대신 "달성 기준 설정하기"를 보여 준다.
 */
public record CriteriaSummary(String state, Integer achievementRate, Integer initialBaselineRate,
		Instant confirmedAt, Instant lastRevisedAt) {

	public static final String CONFIRMED = "CONFIRMED";
	public static final String NOT_CONFIGURED = "NOT_CONFIGURED";
}
