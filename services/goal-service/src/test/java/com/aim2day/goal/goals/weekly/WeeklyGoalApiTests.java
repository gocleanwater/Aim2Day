package com.aim2day.goal.goals.weekly;

import com.aim2day.goal.support.GoalApiClient;
import com.aim2day.goal.support.GoalTestSupport;
import com.aim2day.goal.support.MutableClock;
import com.aim2day.goal.support.TestClockConfig;
import com.aim2day.goal.support.TestSessionConfig;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.session.MapSessionRepository;
import org.springframework.test.web.servlet.MockMvc;

/** 시계는 2026-11-12T03:00:00Z(서울 목요일 12시), 주 시작 요일은 기본 SUNDAY다. */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestSessionConfig.class, TestClockConfig.class})
class WeeklyGoalApiTests {

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
	void reset() {
		clock.reset();
		GoalTestSupport.clean(jdbc);
		api = new GoalApiClient(mockMvc, sessionRepository);
	}

	private long monthly() throws Exception {
		return api.monthly(1L, api.yearly(1L, "연간"), "월간");
	}

	private void addRecord(long weekly, String amount) throws Exception {
		api.expect(api.post("/api/goals/weekly/" + weekly + "/numeric-records", 1L, "{\"amount\":" + amount + "}"),
				201);
	}

	@Test
	void createUsesCurrentWeekFromSettings() throws Exception {
		long monthly = monthly();
		long first = api.weekly(1L, monthly, "일요일 시작");
		api.expect(api.get("/api/goals/weekly/" + first, 1L), 200,
				"$.data.startDate", "2026-11-08", "$.data.endDate", "2026-11-14");

		api.expect(api.put("/api/settings", 1L,
				"{\"weekStartDay\":\"MONDAY\",\"timezone\":\"Asia/Seoul\",\"version\":0}"), 200);
		long second = api.weekly(1L, monthly, "월요일 시작");
		api.expect(api.get("/api/goals/weekly/" + second, 1L), 200,
				"$.data.startDate", "2026-11-09", "$.data.endDate", "2026-11-15");
		// 먼저 만든 주간 목표의 기간은 설정을 바꿔도 그대로다.
		api.expect(api.get("/api/goals/weekly/" + first, 1L), 200,
				"$.data.startDate", "2026-11-08", "$.data.endDate", "2026-11-14");
	}

	@Test
	void checklistWithoutActionsIsNoRecord() throws Exception {
		long monthly = monthly();
		api.expect(api.post("/api/goals/weekly", 1L,
				"{\"monthlyGoalId\":" + monthly + ",\"title\":\"  체크  \",\"description\":\"설명\"}"), 201,
				"$.data.title", "체크",
				"$.data.path.yearlyGoal.title", "연간",
				"$.data.path.monthlyGoal.id", (int) monthly,
				"$.data.path.monthlyGoal.month", "2026-11",
				"$.data.measurementType", "CHECKLIST",
				"$.data.weight", 1.0,
				"$.data.targetValue", null,
				"$.data.currentValue", null,
				"$.data.isTargetReached", null,
				"$.data.progress.state", "NO_RECORD",
				"$.data.progress.rate", null,
				"$.data.actionCounts.total", 0,
				"$.data.actionCounts.completed", 0,
				"$.data.displayColor", "#3B82F6",
				"$.data.status", "PROGRESS",
				"$.data.isDelayed", false,
				"$.data.version", 0);
	}

	@Test
	void createRecalculatesMonthly() throws Exception {
		long monthly = monthly();
		long w1 = api.numericWeekly(1L, monthly, "문제 풀기", "10");
		addRecord(w1, "5");
		api.expect(api.get("/api/goals/monthly/" + monthly, 1L), 200, "$.data.progress.rate", 50.0);

		// 새 주간 목표는 0%로 분모에 들어가 월간이 (50 + 0) / 2 = 25가 된다.
		api.weekly(1L, monthly, "체크형");
		api.expect(api.get("/api/goals/monthly/" + monthly, 1L), 200,
				"$.data.progress.state", "RECORDED", "$.data.progress.rate", 25.0, "$.data.weeklyGoalCount", 2);
		// 월간 진행률이 바뀐 기록이 남는다(50.00 -> 25.00).
		org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
				"SELECT COUNT(*) FROM goal_progress_logs WHERE target_type = 'MONTHLY_GOAL' AND new_rate = 25.00",
				Integer.class)).isEqualTo(1);
	}

	@Test
	void numericRequiresTargetValue() throws Exception {
		long monthly = monthly();
		api.expect(api.post("/api/goals/weekly", 1L,
				"{\"monthlyGoalId\":" + monthly + ",\"title\":\"x\",\"measurementType\":\"NUMERIC\"}"), 400,
				"$.code", "VALIDATION_FAILED",
				"$.details.fieldErrors[0].field", "targetValue",
				"$.details.fieldErrors[0].reason", "REQUIRED");
	}

	@Test
	void checklistWithTargetValueIs400() throws Exception {
		long monthly = monthly();
		api.expect(api.post("/api/goals/weekly", 1L,
				"{\"monthlyGoalId\":" + monthly + ",\"title\":\"x\",\"targetValue\":10}"), 400,
				"$.details.fieldErrors[0].field", "targetValue");
	}

	@Test
	void weightAndTargetRange() throws Exception {
		long monthly = monthly();
		String head = "{\"monthlyGoalId\":" + monthly + ",\"title\":\"x\"";
		api.expect(api.post("/api/goals/weekly", 1L, head + ",\"weight\":0}"), 400,
				"$.details.fieldErrors[0].field", "weight", "$.details.fieldErrors[0].reason", "OUT_OF_RANGE");
		api.expect(api.post("/api/goals/weekly", 1L, head + ",\"weight\":1000}"), 400,
				"$.details.fieldErrors[0].reason", "OUT_OF_RANGE");
		api.expect(api.post("/api/goals/weekly", 1L, head + ",\"weight\":1.234}"), 400,
				"$.details.fieldErrors[0].reason", "INVALID_FORMAT");
		api.expect(api.post("/api/goals/weekly", 1L, head + ",\"measurementType\":\"NUMERIC\",\"targetValue\":0}"),
				400, "$.details.fieldErrors[0].field", "targetValue", "$.details.fieldErrors[0].reason",
				"OUT_OF_RANGE");
		api.expect(api.post("/api/goals/weekly", 1L,
				head + ",\"measurementType\":\"NUMERIC\",\"targetValue\":99999999.999}"), 400,
				"$.details.fieldErrors[0].reason", "INVALID_FORMAT");
		api.expect(api.post("/api/goals/weekly", 1L,
				head + ",\"measurementType\":\"NUMERIC\",\"targetValue\":100000000}"), 400,
				"$.details.fieldErrors[0].reason", "OUT_OF_RANGE");
		// 상한 경계는 허용한다.
		api.expect(api.post("/api/goals/weekly", 1L,
				head + ",\"weight\":999.99,\"measurementType\":\"NUMERIC\",\"targetValue\":99999999.99}"), 201,
				"$.data.targetValue", 99999999.99, "$.data.weight", 999.99);
		api.expect(api.post("/api/goals/weekly", 1L, "{\"monthlyGoalId\":" + monthly + ",\"title\":\"y\","
				+ "\"measurementType\":\"BOGUS\"}"), 400, "$.code", "MALFORMED_REQUEST");
	}

	@Test
	void monthBoundaryWeekAcceptsEitherMonth() throws Exception {
		clock.set(Instant.parse("2026-11-30T03:00:00Z"));
		long yearly = api.yearly(1L, "연간");
		long november = api.monthly(1L, yearly, "11월");
		clock.set(Instant.parse("2026-12-01T03:00:00Z"));
		long december = api.monthly(1L, yearly, "12월");

		// 이번 주는 SUNDAY 시작 11-29 ~ 12-05. 11월과 12월 월간 목표 모두와 겹친다.
		long underNovember = api.weekly(1L, november, "11월 쪽");
		long underDecember = api.weekly(1L, december, "12월 쪽");
		api.expect(api.get("/api/goals/weekly/" + underNovember, 1L), 200,
				"$.data.startDate", "2026-11-29", "$.data.endDate", "2026-12-05",
				"$.data.path.monthlyGoal.month", "2026-11");
		api.expect(api.get("/api/goals/weekly/" + underDecember, 1L), 200,
				"$.data.path.monthlyGoal.month", "2026-12");
	}

	@Test
	void parentMonthNotOverlappingWeekIs422() throws Exception {
		clock.set(Instant.parse("2026-10-15T03:00:00Z"));
		long october = api.monthly(1L, api.yearly(1L, "연간"), "10월");
		clock.reset();
		api.expect(api.post("/api/goals/weekly", 1L,
				"{\"monthlyGoalId\":" + october + ",\"title\":\"x\"}"), 422,
				"$.code", "GOAL_PARENT_PERIOD_MISMATCH",
				"$.details.parentStartDate", "2026-10-01",
				"$.details.parentEndDate", "2026-10-31");
	}

	@Test
	void parentUnavailableCases() throws Exception {
		long monthly = monthly();
		api.expect(api.post("/api/goals/weekly", 2L, "{\"monthlyGoalId\":" + monthly + ",\"title\":\"x\"}"), 422,
				"$.code", "GOAL_PARENT_UNAVAILABLE",
				"$.details.parentLevel", "MONTHLY", "$.details.parentId", (int) monthly);
		api.expect(api.post("/api/goals/weekly", 1L, "{\"title\":\"x\"}"), 400,
				"$.details.fieldErrors[0].field", "monthlyGoalId");
	}

	@Test
	void listByDateAndMonthly() throws Exception {
		long yearly = api.yearly(1L, "연간");
		long m1 = api.monthly(1L, yearly, "첫 월간");
		long m2 = api.monthly(1L, yearly, "둘째 월간");
		long w1 = api.weekly(1L, m1, "w1");
		long w2 = api.weekly(1L, m2, "w2");
		api.expect(api.get("/api/goals/weekly?date=2026-11-12", 1L), 200,
				"$.data.length()", 2, "$.data[0].id", (int) w1, "$.data[1].id", (int) w2);
		api.expect(api.get("/api/goals/weekly?date=2026-11-20", 1L), 200, "$.data.length()", 0);
		api.expect(api.get("/api/goals/weekly?date=2026-11-12&monthlyGoalId=" + m2, 1L), 200,
				"$.data.length()", 1, "$.data[0].id", (int) w2);
		// date를 생략하면 오늘(2026-11-12)이다.
		api.expect(api.get("/api/goals/weekly", 1L), 200, "$.data.length()", 2);
		api.expect(api.get("/api/goals/weekly?date=11-12", 1L), 400, "$.code", "VALIDATION_FAILED");
		api.expect(api.get("/api/goals/weekly?date=2026-11-12", 2L), 200, "$.data.length()", 0);
	}

	@Test
	void detailHasEmptyActionsForNow() throws Exception {
		long weekly = api.weekly(1L, monthly(), "주간");
		api.expect(api.get("/api/goals/weekly/" + weekly, 1L), 200,
				"$.data.id", (int) weekly, "$.data.actions.length()", 0);
	}

	@Test
	void patchChangesOnlySentFieldsAndChecksVersion() throws Exception {
		long weekly = api.weekly(1L, monthly(), "주간");
		api.expect(api.patch("/api/goals/weekly/" + weekly, 1L,
				"{\"title\":\"새 제목\",\"description\":\"설명\",\"color\":\"#abcdef\",\"version\":0}"), 200,
				"$.data.title", "새 제목", "$.data.description", "설명", "$.data.color", "#ABCDEF",
				"$.data.displayColor", "#ABCDEF", "$.data.weight", 1.0, "$.data.version", 1);
		api.expect(api.patch("/api/goals/weekly/" + weekly, 1L, "{\"color\":null,\"version\":1}"), 200,
				"$.data.color", null, "$.data.displayColor", "#3B82F6");
		api.expect(api.patch("/api/goals/weekly/" + weekly, 1L, "{\"title\":\"낡은 화면\",\"version\":0}"), 409,
				"$.code", "VERSION_CONFLICT", "$.details.currentVersion", 2);
		api.expect(api.patch("/api/goals/weekly/" + weekly, 1L, "{\"title\":null,\"version\":2}"), 400,
				"$.details.fieldErrors[0].field", "title");
		api.expect(api.patch("/api/goals/weekly/" + weekly, 1L, "{\"weight\":null,\"version\":2}"), 400,
				"$.details.fieldErrors[0].field", "weight");
	}

	@Test
	void patchMeasurementTypeIs422() throws Exception {
		long weekly = api.numericWeekly(1L, monthly(), "수치", "10");
		api.expect(api.patch("/api/goals/weekly/" + weekly, 1L,
				"{\"measurementType\":\"CHECKLIST\",\"version\":0}"), 422,
				"$.code", "GOAL_MEASUREMENT_TYPE_IMMUTABLE");
		// 같은 값은 무시한다.
		api.expect(api.patch("/api/goals/weekly/" + weekly, 1L,
				"{\"measurementType\":\"NUMERIC\",\"title\":\"같은 방식\",\"version\":0}"), 200,
				"$.data.title", "같은 방식", "$.data.measurementType", "NUMERIC");
	}

	@Test
	void patchTargetValueOnChecklistIs422NotNumeric() throws Exception {
		long weekly = api.weekly(1L, monthly(), "체크");
		api.expect(api.patch("/api/goals/weekly/" + weekly, 1L, "{\"targetValue\":10,\"version\":0}"), 422,
				"$.code", "GOAL_NOT_NUMERIC");
	}

	@Test
	void patchExplicitNullTargetValueOnChecklistIs422ButOmittedIs200() throws Exception {
		long weekly = api.weekly(1L, monthly(), "체크");
		api.expect(api.patch("/api/goals/weekly/" + weekly, 1L, "{\"targetValue\":null,\"version\":0}"), 422,
				"$.code", "GOAL_NOT_NUMERIC");
		api.expect(api.patch("/api/goals/weekly/" + weekly, 1L, "{\"title\":\"새 제목\",\"version\":0}"), 200,
				"$.data.title", "새 제목");
	}

	@Test
	void patchTargetValueRecalculates() throws Exception {
		long monthly = monthly();
		long weekly = api.numericWeekly(1L, monthly, "수치", "10");
		addRecord(weekly, "5");
		// 기록은 version을 올리지 않으므로 처음 읽은 version 0으로 수정할 수 있다.
		api.expect(api.patch("/api/goals/weekly/" + weekly, 1L, "{\"targetValue\":20,\"version\":0}"), 200,
				"$.data.targetValue", 20.0, "$.data.currentValue", 5.0, "$.data.progress.rate", 25.0,
				"$.data.isTargetReached", false);
		api.expect(api.get("/api/goals/monthly/" + monthly, 1L), 200, "$.data.progress.rate", 25.0);

		api.expect(api.patch("/api/goals/weekly/" + weekly, 1L, "{\"targetValue\":0,\"version\":1}"), 400,
				"$.details.fieldErrors[0].reason", "OUT_OF_RANGE");
		api.expect(api.patch("/api/goals/weekly/" + weekly, 1L, "{\"targetValue\":null,\"version\":1}"), 400,
				"$.details.fieldErrors[0].reason", "REQUIRED");
	}

	@Test
	void patchWeightRecalculatesMonthly() throws Exception {
		long monthly = monthly();
		long w1 = api.numericWeekly(1L, monthly, "수치", "10");
		api.weekly(1L, monthly, "체크");
		addRecord(w1, "10");
		api.expect(api.get("/api/goals/monthly/" + monthly, 1L), 200, "$.data.progress.rate", 50.0);
		// 100% 주간의 가중치를 3으로 올리면 (100 x 3 + 0 x 1) / 4 = 75.
		api.expect(api.patch("/api/goals/weekly/" + w1, 1L, "{\"weight\":3,\"version\":0}"), 200,
				"$.data.weight", 3.0);
		api.expect(api.get("/api/goals/monthly/" + monthly, 1L), 200, "$.data.progress.rate", 75.0);
	}

	@Test
	void moveToOtherMonthlyRecalculatesBoth() throws Exception {
		clock.set(Instant.parse("2026-11-30T03:00:00Z"));
		long yearly = api.yearly(1L, "연간");
		long november = api.monthly(1L, yearly, "11월");
		clock.set(Instant.parse("2026-12-01T03:00:00Z"));
		long december = api.monthly(1L, yearly, "12월");
		long weekly = api.numericWeekly(1L, november, "경계 주", "10");
		addRecord(weekly, "5");
		api.expect(api.get("/api/goals/monthly/" + november, 1L), 200, "$.data.progress.rate", 50.0);

		api.expect(api.patch("/api/goals/weekly/" + weekly, 1L,
				"{\"monthlyGoalId\":" + december + ",\"version\":0}"), 200,
				"$.data.path.monthlyGoal.id", (int) december);

		// 이전 월간은 하위가 없어져 기록 없음, 새 월간에 진행률이 반영된다.
		api.expect(api.get("/api/goals/monthly/" + november, 1L), 200,
				"$.data.progress.state", "NO_RECORD", "$.data.weeklyGoalCount", 0);
		api.expect(api.get("/api/goals/monthly/" + december, 1L), 200,
				"$.data.progress.state", "RECORDED", "$.data.progress.rate", 50.0, "$.data.weeklyGoalCount", 1);
		// 이전 월간의 저장된 진행률도 0으로 돌아가고 기록이 남는다.
		org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
				"SELECT progress_rate FROM monthly_goals WHERE id = ?", java.math.BigDecimal.class, november))
				.isEqualByComparingTo("0.00");
	}

	@Test
	void moveToNonOverlappingOrForeignMonthlyIs422() throws Exception {
		long yearly = api.yearly(1L, "연간");
		long november = api.monthly(1L, yearly, "11월");
		long october = GoalTestSupport.seedMonthly(jdbc, yearly, java.time.LocalDate.of(2026, 10, 1),
				java.time.LocalDate.of(2026, 10, 31), "10월");
		long foreign = api.monthly(2L, api.yearly(2L, "남의 연간"), "남의 월간");
		long weekly = api.weekly(1L, november, "주간");

		api.expect(api.patch("/api/goals/weekly/" + weekly, 1L,
				"{\"monthlyGoalId\":" + october + ",\"version\":0}"), 422, "$.code", "GOAL_PARENT_PERIOD_MISMATCH");
		api.expect(api.patch("/api/goals/weekly/" + weekly, 1L,
				"{\"monthlyGoalId\":" + foreign + ",\"version\":0}"), 422,
				"$.code", "GOAL_PARENT_UNAVAILABLE", "$.details.parentLevel", "MONTHLY");
		api.expect(api.get("/api/goals/weekly/" + weekly, 1L), 200, "$.data.path.monthlyGoal.id", (int) november);
	}

	@Test
	void hiddenByDeletedAncestor() throws Exception {
		long yearly = api.yearly(1L, "연간");
		long monthly = api.monthly(1L, yearly, "월간");
		long weekly = api.numericWeekly(1L, monthly, "수치", "10");
		jdbc.update("UPDATE yearly_goals SET deleted_at = CURRENT_TIMESTAMP WHERE id = ?", yearly);

		api.expect(api.get("/api/goals/weekly/" + weekly, 1L), 404, "$.details.resource", "WEEKLY_GOAL");
		api.expect(api.get("/api/goals/weekly?date=2026-11-12", 1L), 200, "$.data.length()", 0);
		api.expect(api.patch("/api/goals/weekly/" + weekly, 1L, "{\"title\":\"x\",\"version\":0}"), 404);
		api.expect(api.post("/api/goals/weekly/" + weekly + "/numeric-records", 1L, "{\"amount\":1}"), 404);
		api.expect(api.get("/api/goals/weekly/" + weekly + "/numeric-records", 1L), 404);
		api.expect(api.post("/api/goals/weekly", 1L, "{\"monthlyGoalId\":" + monthly + ",\"title\":\"새\"}"), 422,
				"$.code", "GOAL_PARENT_UNAVAILABLE", "$.details.parentLevel", "MONTHLY");
	}

	@Test
	void hiddenByDeletedMonthly() throws Exception {
		long monthly = monthly();
		long weekly = api.weekly(1L, monthly, "주간");
		jdbc.update("UPDATE monthly_goals SET deleted_at = CURRENT_TIMESTAMP WHERE id = ?", monthly);
		api.expect(api.get("/api/goals/weekly/" + weekly, 1L), 404);
	}

	@Test
	void otherUsersWeeklyIs404() throws Exception {
		long weekly = api.numericWeekly(1L, monthly(), "수치", "10");
		api.expect(api.get("/api/goals/weekly/" + weekly, 2L), 404,
				"$.code", "RESOURCE_NOT_FOUND", "$.details.resource", "WEEKLY_GOAL", "$.details.id", (int) weekly);
		api.expect(api.patch("/api/goals/weekly/" + weekly, 2L, "{\"title\":\"탈취\",\"version\":0}"), 404);
		api.expect(api.post("/api/goals/weekly/" + weekly + "/numeric-records", 2L, "{\"amount\":1}"), 404);
	}
}
