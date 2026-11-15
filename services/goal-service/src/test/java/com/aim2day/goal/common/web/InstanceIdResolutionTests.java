package com.aim2day.goal.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

// 스프링 컨텍스트 없이 호스트명 결정 로직만 검증한다.
class InstanceIdResolutionTests {

	@Test
	void envValueIsUsedWithoutCallingFallback() {
		AtomicBoolean called = new AtomicBoolean(false);

		String id = InstanceIdFilter.resolveInstanceId("goal-service-1", () -> {
			called.set(true);
			return "other";
		});

		assertThat(id).isEqualTo("goal-service-1");
		assertThat(called).isFalse();
	}

	@Test
	void nullEnvUsesFallback() {
		assertThat(InstanceIdFilter.resolveInstanceId(null, () -> "local-host")).isEqualTo("local-host");
	}

	@Test
	void blankEnvUsesFallback() {
		assertThat(InstanceIdFilter.resolveInstanceId("   ", () -> "local-host")).isEqualTo("local-host");
	}

	@Test
	void fallbackExceptionFailsWithCause() {
		Exception original = new java.net.UnknownHostException("no host");

		assertThatThrownBy(() -> InstanceIdFilter.resolveInstanceId(null, () -> {
			throw original;
		})).isInstanceOf(IllegalStateException.class).hasCause(original);
	}

	@Test
	void blankFallbackFails() {
		assertThatThrownBy(() -> InstanceIdFilter.resolveInstanceId(null, () -> "  "))
				.isInstanceOf(IllegalStateException.class);
	}
}
