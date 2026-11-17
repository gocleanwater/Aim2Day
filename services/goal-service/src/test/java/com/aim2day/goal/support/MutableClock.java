package com.aim2day.goal.support;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** 테스트가 시각을 바꿔 가며 쓰는 시계. 같은 스프링 컨텍스트를 여러 테스트 클래스가 공유할 수 있게 한다. */
public class MutableClock extends Clock {

	public static final Instant DEFAULT = Instant.parse("2026-11-12T03:00:00Z");

	private volatile Instant now = DEFAULT;

	public void set(Instant instant) {
		this.now = instant;
	}

	public void reset() {
		this.now = DEFAULT;
	}

	@Override
	public ZoneId getZone() {
		return ZoneOffset.UTC;
	}

	@Override
	public Clock withZone(ZoneId zone) {
		// 시간대별 "오늘"을 실제로 계산하도록 같은 시각을 가리키는 시계를 돌려준다(this를 돌려주면 항상 UTC 날짜가 된다).
		MutableClock self = this;
		return new Clock() {
			@Override
			public ZoneId getZone() {
				return zone;
			}

			@Override
			public Clock withZone(ZoneId other) {
				return self.withZone(other);
			}

			@Override
			public Instant instant() {
				return self.instant();
			}
		};
	}

	@Override
	public Instant instant() {
		return now;
	}
}
