package com.aim2day.goal.onboarding;

import static org.assertj.core.api.Assertions.assertThat;

import com.aim2day.goal.support.GoalApiClient;
import com.aim2day.goal.support.GoalTestSupport;
import com.aim2day.goal.support.MutableClock;
import com.aim2day.goal.support.TestClockConfig;
import com.aim2day.goal.support.TestSessionConfig;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.session.MapSessionRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 온보딩 API 테스트 공통 준비. 시계는 2026-11-12T03:00Z(서울 목요일 12:00)이고 주 시작은 일요일이라 이번 주는 11-08~11-14다.
 * 사용자 1~3이 있다. 임시 저장 테이블은 GoalTestSupport.clean이 비우지 않으므로 여기서 먼저 비운다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestSessionConfig.class, TestClockConfig.class})
abstract class OnboardingTestBase {

	static final String DRAFT = "/api/goals/onboarding/draft";
	static final String COMPLETE = "/api/goals/onboarding/complete";
	static final String STATUS = "/api/goals/onboarding/status";

	@Autowired
	MockMvc mockMvc;
	@Autowired
	MapSessionRepository sessionRepository;
	@Autowired
	JdbcTemplate jdbc;
	@Autowired
	MutableClock clock;

	GoalApiClient api;

	@BeforeEach
	void cleanAll() {
		clock.reset();
		jdbc.update("DELETE FROM onboarding_drafts");
		GoalTestSupport.clean(jdbc);
		api = new GoalApiClient(mockMvc, sessionRepository);
	}

	/** 첫 저장의 version. 사용자별 카운터(테스트마다 user_settings를 비우므로 0)에 1을 더한 값이다. */
	long v0() {
		return 1L;
	}

	int count(String sql, Object... args) {
		return jdbc.queryForObject(sql, Integer.class, args);
	}

	/** 임시 저장을 API로 만든다(200 확인). */
	void putDraft(long userId, int step, String draftDataJson) throws Exception {
		MvcResult result = api.put(DRAFT, userId, "{\"currentStep\":" + step + ",\"draftData\":" + draftDataJson + "}");
		assertThat(api.status(result)).as(api.body(result)).isEqualTo(200);
	}

	/** VALIDATION_FAILED의 fieldErrors에 (field, reason)이 있는지 확인한다. */
	void assertFieldError(MvcResult result, String field, String reason) throws Exception {
		assertThat(api.status(result)).as(api.body(result)).isEqualTo(400);
		List<Map<String, String>> errors = JsonPath.read(api.body(result), "$.details.fieldErrors");
		assertThat(errors).as(api.body(result))
				.anyMatch(e -> field.equals(e.get("field")) && reason.equals(e.get("reason")));
	}
}
