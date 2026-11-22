package com.aim2day.goal.common.time;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * DB의 DATETIME(UTC)과 응답의 Instant 사이 변환. user-service처럼 엔티티는 LocalDateTime(UTC)로 매핑하고,
 * 응답 DTO에서만 Instant로 바꿔 JSON이 항상 'Z'로 끝나게 한다.
 */
public final class UtcTime {

	private UtcTime() {
	}

	/** 앱이 직접 저장하는 일시(확정·달성 시각 등)는 주입한 Clock에서만 얻는다. */
	public static LocalDateTime now(Clock clock) {
		return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
	}

	/** UTC 기준 LocalDateTime을 응답용 Instant로 바꾼다. null이면 null을 돌려준다(예: 완료 전의 completedAt). */
	public static Instant toInstant(LocalDateTime utc) {
		return utc == null ? null : utc.toInstant(ZoneOffset.UTC);
	}
}
