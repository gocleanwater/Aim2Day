package com.aim2day.goal.onboarding;

import static org.assertj.core.api.Assertions.assertThat;

import com.aim2day.goal.support.ApiTestBase;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 온보딩 API 테스트 공통 준비. 시계는 2026-11-12T03:00Z(서울 목요일 12:00)이고 주 시작은 일요일이라 이번 주는 11-08~11-14다.
 * 사용자 1~3이 있다(임시 저장 테이블 청소는 GoalTestSupport.clean이 한다).
 */
abstract class OnboardingTestBase extends ApiTestBase {

	static final String DRAFT = "/api/goals/onboarding/draft";
	static final String COMPLETE = "/api/goals/onboarding/complete";
	static final String STATUS = "/api/goals/onboarding/status";

	/** 첫 저장의 version. 사용자별 카운터(테스트마다 user_settings를 비우므로 0)에 1을 더한 값이다. */
	long firstVersion() {
		return 1L;
	}

	/** 임시 저장을 API로 만든다(200 확인). */
	void putDraft(long userId, int step, String draftDataJson) throws Exception {
		MvcResult result = api.put(DRAFT, userId, "{\"currentStep\":" + step + ",\"draftData\":" + draftDataJson + "}");
		assertThat(api.status(result)).as(api.body(result)).isEqualTo(200);
	}
}
