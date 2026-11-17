package com.aim2day.goal.goals.weekly.record;

import static org.assertj.core.api.Assertions.assertThat;

import com.aim2day.goal.support.GoalApiClient;
import com.aim2day.goal.support.GoalTestSupport;
import com.aim2day.goal.support.MutableClock;
import com.aim2day.goal.support.TestClockConfig;
import com.aim2day.goal.support.TestSessionConfig;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.session.MapSessionRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@Import({TestSessionConfig.class, TestClockConfig.class})
class NumericRecordApiTests {

	@Autowired
	MockMvc mockMvc;
	@Autowired
	MapSessionRepository sessionRepository;
	@Autowired
	JdbcTemplate jdbc;
	@Autowired
	MutableClock clock;

	GoalApiClient api;
	long monthly;

	@BeforeEach
	void reset() throws Exception {
		clock.reset();
		GoalTestSupport.clean(jdbc);
		api = new GoalApiClient(mockMvc, sessionRepository);
		monthly = api.monthly(1L, api.yearly(1L, "연간"), "월간");
	}

	private String path(long weekly) {
		return "/api/goals/weekly/" + weekly + "/numeric-records";
	}

	private BigDecimal currentValue(long weekly) {
		return jdbc.queryForObject("SELECT current_value FROM weekly_goals WHERE id = ?", BigDecimal.class, weekly);
	}

	@Test
	void directInputAddsAndReturnsRecordAndGoal() throws Exception {
		long weekly = api.numericWeekly(1L, monthly, "문제 풀기", "10");
		MvcResult result = api.post(path(weekly), 1L, "{\"amount\":3,\"memo\":\"주말 스터디\"}");
		api.expect(result, 201,
				"$.data.record.source", "DIRECT_INPUT",
				"$.data.record.action", null,
				"$.data.record.amount", 3.0,
				"$.data.record.memo", "주말 스터디",
				"$.data.weeklyGoal.id", (int) weekly,
				"$.data.weeklyGoal.currentValue", 3.0,
				"$.data.weeklyGoal.progress.state", "RECORDED",
				"$.data.weeklyGoal.progress.rate", 30.0,
				"$.data.weeklyGoal.isTargetReached", false);
		assertThat((String) GoalTestSupport.read(result, "$.data.record.createdAt")).endsWith("Z");
		// 월간도 같은 트랜잭션에서 다시 계산된다.
		api.expect(api.get("/api/goals/monthly/" + monthly, 1L), 200, "$.data.progress.rate", 30.0);
	}

	@Test
	void overTargetKeepsRawValue() throws Exception {
		long weekly = api.numericWeekly(1L, monthly, "문제 풀기", "10");
		api.expect(api.post(path(weekly), 1L, "{\"amount\":14}"), 201,
				"$.data.weeklyGoal.currentValue", 14.0,
				"$.data.weeklyGoal.progress.rate", 100.0,
				"$.data.weeklyGoal.isTargetReached", true);
	}

	@Test
	void targetReachedUsesRawCompare() throws Exception {
		long weekly = api.numericWeekly(1L, monthly, "큰 목표", "10000");
		api.expect(api.post(path(weekly), 1L, "{\"amount\":9999.99}"), 201,
				"$.data.weeklyGoal.progress.rate", 100.0,
				"$.data.weeklyGoal.isTargetReached", false);
	}

	@Test
	void checklistGoalIs422NotNumeric() throws Exception {
		long weekly = api.weekly(1L, monthly, "체크");
		api.expect(api.post(path(weekly), 1L, "{\"amount\":1}"), 422, "$.code", "GOAL_NOT_NUMERIC");
	}

	@Test
	void amountZeroOrNegativeIs400() throws Exception {
		long weekly = api.numericWeekly(1L, monthly, "수치", "10");
		api.expect(api.post(path(weekly), 1L, "{\"amount\":0}"), 400,
				"$.code", "VALIDATION_FAILED",
				"$.details.fieldErrors[0].field", "amount",
				"$.details.fieldErrors[0].reason", "OUT_OF_RANGE");
		api.expect(api.post(path(weekly), 1L, "{\"amount\":-2}"), 400,
				"$.details.fieldErrors[0].reason", "OUT_OF_RANGE");
		api.expect(api.post(path(weekly), 1L, "{}"), 400, "$.details.fieldErrors[0].reason", "REQUIRED");
		api.expect(api.post(path(weekly), 1L, "{\"amount\":\"abc\"}"), 400, "$.code", "MALFORMED_REQUEST");
	}

	@Test
	void amountThreeDecimalsIs400() throws Exception {
		long weekly = api.numericWeekly(1L, monthly, "수치", "10");
		api.expect(api.post(path(weekly), 1L, "{\"amount\":1.005}"), 400,
				"$.details.fieldErrors[0].field", "amount", "$.details.fieldErrors[0].reason", "INVALID_FORMAT");
		// 뒤쪽 0은 셋째 자리로 보지 않는다.
		api.expect(api.post(path(weekly), 1L, "{\"amount\":1.500}"), 201, "$.data.record.amount", 1.5);
	}

	@Test
	void memoOver255Is400() throws Exception {
		long weekly = api.numericWeekly(1L, monthly, "수치", "10");
		api.expect(api.post(path(weekly), 1L, "{\"amount\":1,\"memo\":\"" + "가".repeat(256) + "\"}"), 400,
				"$.details.fieldErrors[0].field", "memo", "$.details.fieldErrors[0].reason", "TOO_LONG");
	}

	@Test
	void sumOverflowIs400() throws Exception {
		long weekly = api.numericWeekly(1L, monthly, "수치", "10");
		api.expect(api.post(path(weekly), 1L, "{\"amount\":99999999.99}"), 201);
		api.expect(api.post(path(weekly), 1L, "{\"amount\":0.01}"), 400,
				"$.code", "VALIDATION_FAILED", "$.details.fieldErrors[0].field", "amount",
				"$.details.fieldErrors[0].reason", "OUT_OF_RANGE");
		assertThat(currentValue(weekly)).isEqualByComparingTo("99999999.99");
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM weekly_goal_numeric_records", Integer.class))
				.isEqualTo(1);
	}

	@Test
	void deleteDirectRecordSubtracts() throws Exception {
		long weekly = api.numericWeekly(1L, monthly, "수치", "10");
		api.expect(api.post(path(weekly), 1L, "{\"amount\":3}"), 201);
		MvcResult second = api.post(path(weekly), 1L, "{\"amount\":4}");
		long recordId = ((Number) GoalTestSupport.read(second, "$.data.record.id")).longValue();
		api.expect(second, 201, "$.data.weeklyGoal.currentValue", 7.0, "$.data.weeklyGoal.progress.rate", 70.0);

		api.expect(api.delete(path(weekly) + "/" + recordId, 1L), 200,
				"$.data.weeklyGoal.currentValue", 3.0, "$.data.weeklyGoal.progress.rate", 30.0);
		assertThat(currentValue(weekly)).isEqualByComparingTo("3.00");
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM weekly_goal_numeric_records WHERE id = ?",
				Integer.class, recordId)).isZero();
		api.expect(api.get("/api/goals/monthly/" + monthly, 1L), 200, "$.data.progress.rate", 30.0);
		// 삭제 뒤 같은 기록을 다시 지우면 404다.
		api.expect(api.delete(path(weekly) + "/" + recordId, 1L), 404,
				"$.details.resource", "NUMERIC_RECORD", "$.details.id", (int) recordId);
	}

	@Test
	void deleteActionCompletionRecordIs422() throws Exception {
		long weekly = api.numericWeekly(1L, monthly, "수치", "10");
		// action_id는 actions(id) FK라 실제 행동 행이 필요하다.
		long action = GoalTestSupport.seedAction(jdbc, 1L, weekly, "문제 2개", java.time.LocalDate.of(2026, 11, 12),
				true, false);
		jdbc.update("INSERT INTO weekly_goal_numeric_records (weekly_goal_id, action_id, record_source, amount) "
				+ "VALUES (?, ?, 'ACTION_COMPLETION', 2.00)", weekly, action);
		jdbc.update("UPDATE weekly_goals SET current_value = 2.00, progress_rate = 20.00 WHERE id = ?", weekly);
		long recordId = jdbc.queryForObject("SELECT id FROM weekly_goal_numeric_records", Long.class);

		api.expect(api.delete(path(weekly) + "/" + recordId, 1L), 422, "$.code", "RECORD_NOT_DELETABLE");
		assertThat(currentValue(weekly)).isEqualByComparingTo("2.00");

		api.expect(api.get(path(weekly), 1L), 200,
				"$.data[0].source", "ACTION_COMPLETION", "$.data[0].action.id", (int) action,
				"$.data[0].action.title", "문제 2개");
	}

	@Test
	void recordOfOtherGoalIs404() throws Exception {
		long weekly = api.numericWeekly(1L, monthly, "수치", "10");
		long other = api.numericWeekly(1L, monthly, "다른 수치", "10");
		MvcResult added = api.post(path(other), 1L, "{\"amount\":2}");
		long recordId = ((Number) GoalTestSupport.read(added, "$.data.record.id")).longValue();

		// 다른 주간 목표의 기록 id로는 지울 수 없다.
		api.expect(api.delete(path(weekly) + "/" + recordId, 1L), 404, "$.code", "RESOURCE_NOT_FOUND");
		// 다른 사용자는 주간 목표 자체가 404다.
		api.expect(api.delete(path(other) + "/" + recordId, 2L), 404, "$.details.resource", "WEEKLY_GOAL");
		api.expect(api.get(path(other), 2L), 404);
		assertThat(currentValue(other)).isEqualByComparingTo("2.00");
	}

	@Test
	void listNewestFirst() throws Exception {
		long weekly = api.numericWeekly(1L, monthly, "수치", "100");
		api.expect(api.get(path(weekly), 1L), 200, "$.data.length()", 0);
		api.post(path(weekly), 1L, "{\"amount\":1,\"memo\":\"첫째\"}");
		api.post(path(weekly), 1L, "{\"amount\":2,\"memo\":\"둘째\"}");
		api.post(path(weekly), 1L, "{\"amount\":3,\"memo\":\"셋째\"}");
		// 같은 시각(초 단위)이면 id가 큰 쪽이 나중이다.
		api.expect(api.get(path(weekly), 1L), 200,
				"$.data.length()", 3, "$.data[0].memo", "셋째", "$.data[1].memo", "둘째", "$.data[2].memo", "첫째");
	}

	@Test
	void titlePatchDoesNotOverwriteCurrentValue() throws Exception {
		long weekly = api.numericWeekly(1L, monthly, "수치", "10");
		// 제목 수정 화면이 version 0으로 열려 있는 동안 다른 인스턴스가 수치를 기록했다.
		api.expect(api.post(path(weekly), 1L, "{\"amount\":3}"), 201);
		api.expect(api.patch("/api/goals/weekly/" + weekly, 1L, "{\"title\":\"새 제목\",\"version\":0}"), 200,
				"$.data.title", "새 제목", "$.data.currentValue", 3.0, "$.data.progress.rate", 30.0,
				"$.data.version", 1);
		assertThat(currentValue(weekly)).isEqualByComparingTo("3.00");
	}

	@Test
	void recordsAreLoggedWithTitles() throws Exception {
		long weekly = api.numericWeekly(1L, monthly, "문제 풀기", "10");
		api.expect(api.post(path(weekly), 1L, "{\"amount\":3}"), 201);
		MvcResult logs = api.get("/api/goals/progress-logs", 1L);
		api.expect(logs, 200, "$.data.length()", 2);
		assertThat(api.body(logs)).contains("\"targetTitle\":\"문제 풀기\"").contains("\"targetTitle\":\"월간\"")
				.contains("\"memo\":\"수치 기록: +3\"");
		// 삭제하면 반대 방향 기록이 주간·월간 각각 남는다(같은 메모).
		MvcResult list = api.get(path(weekly), 1L);
		long recordId = ((Number) GoalTestSupport.read(list, "$.data[0].id")).longValue();
		api.expect(api.delete(path(weekly) + "/" + recordId, 1L), 200);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM goal_progress_logs WHERE memo = '수치 기록 삭제: -3'",
				Integer.class)).isEqualTo(2);
	}
}
