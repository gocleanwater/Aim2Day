package com.aim2day.goal.support;

import static org.assertj.core.api.Assertions.assertThat;

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
 * 도메인별 API 테스트 기반 클래스의 공통 뿌리. 환경 초기화만 맡는다: Spring 테스트 설정, 주입 필드,
 * 시계 재설정, DB 청소, GoalApiClient 생성. 도메인별 시드(목표 트리, 설정 등)는 각 기반 클래스가 @BeforeEach로 이어 한다.
 * 슈퍼클래스의 @BeforeEach가 먼저 실행되므로 시계 재설정 -> DB 청소 -> 클라이언트 생성 순서가 항상 시드보다 앞선다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestSessionConfig.class, TestClockConfig.class})
public abstract class ApiTestBase {

	@Autowired
	protected MockMvc mockMvc;
	@Autowired
	protected MapSessionRepository sessionRepository;
	@Autowired
	protected JdbcTemplate jdbc;
	@Autowired
	protected MutableClock clock;

	protected GoalApiClient api;

	@BeforeEach
	void resetEnvironment() {
		clock.reset();
		GoalTestSupport.clean(jdbc);
		api = new GoalApiClient(mockMvc, sessionRepository);
	}

	protected int count(String sql, Object... args) {
		return jdbc.queryForObject(sql, Integer.class, args);
	}

	/** VALIDATION_FAILED의 fieldErrors에 (field, reason)이 있는지 확인한다. */
	protected void assertFieldError(MvcResult result, String field, String reason) throws Exception {
		assertThat(api.status(result)).as(api.body(result)).isEqualTo(400);
		List<Map<String, String>> errors = JsonPath.read(api.body(result), "$.details.fieldErrors");
		assertThat(errors).as(api.body(result))
				.anyMatch(e -> field.equals(e.get("field")) && reason.equals(e.get("reason")));
	}
}
