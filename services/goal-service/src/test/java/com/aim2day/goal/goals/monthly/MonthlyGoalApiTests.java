package com.aim2day.goal.goals.monthly;

import static org.assertj.core.api.Assertions.assertThat;

import com.aim2day.goal.support.GoalApiClient;
import com.aim2day.goal.support.GoalTestSupport;
import com.aim2day.goal.support.MutableClock;
import com.aim2day.goal.support.TestClockConfig;
import com.aim2day.goal.support.TestSessionConfig;
import java.math.BigDecimal;
import java.time.LocalDate;
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

/** 시계는 2026-11-12T03:00:00Z(서울 목요일 12시), 주 시작 요일은 기본 SUNDAY다. */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestSessionConfig.class, TestClockConfig.class})
class MonthlyGoalApiTests {

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

	@Test
	void createUsesCurrentMonth() throws Exception {
		long yearly = api.yearly(1L, "백엔드");
		MvcResult result = api.post("/api/goals/monthly", 1L,
				"{\"yearlyGoalId\":" + yearly + ",\"title\":\"알고리즘 기초 다지기\",\"description\":\"자료구조 복습\"}");
		api.expect(result, 201,
				"$.code", "SUCCESS",
				"$.data.yearlyGoal.id", (int) yearly,
				"$.data.yearlyGoal.title", "백엔드",
				"$.data.month", "2026-11",
				"$.data.startDate", "2026-11-01",
				"$.data.endDate", "2026-11-30",
				"$.data.title", "알고리즘 기초 다지기",
				"$.data.color", null,
				"$.data.displayColor", "#3B82F6",
				"$.data.status", "PROGRESS",
				"$.data.pathStatus", null,
				"$.data.isDelayed", false,
				"$.data.progress.state", "NO_RECORD",
				"$.data.progress.rate", null,
				"$.data.weeklyGoalCount", 0,
				"$.data.version", 0);
	}

	@Test
	void createRejectsBadInput() throws Exception {
		long yearly = api.yearly(1L, "백엔드");
		api.expect(api.post("/api/goals/monthly", 1L, "{\"title\":\"x\"}"), 400,
				"$.code", "VALIDATION_FAILED", "$.details.fieldErrors[0].field", "yearlyGoalId");
		api.expect(api.post("/api/goals/monthly", 1L, "{\"yearlyGoalId\":" + yearly + ",\"title\":\"  \"}"), 400,
				"$.details.fieldErrors[0].field", "title", "$.details.fieldErrors[0].reason", "REQUIRED");
		api.expect(api.post("/api/goals/monthly", 1L,
				"{\"yearlyGoalId\":" + yearly + ",\"title\":\"x\",\"color\":\"red\"}"), 400,
				"$.details.fieldErrors[0].field", "color");
	}

	@Test
	void parentOfNextYearIs422PeriodMismatch() throws Exception {
		long next = api.yearly(1L, "새해", ",\"targetYear\":2027");
		api.expect(api.post("/api/goals/monthly", 1L, "{\"yearlyGoalId\":" + next + ",\"title\":\"x\"}"), 422,
				"$.code", "GOAL_PARENT_PERIOD_MISMATCH",
				"$.details.parentStartDate", "2027-01-01",
				"$.details.parentEndDate", "2027-12-31");
	}

	@Test
	void otherUsersParentIs422Unavailable() throws Exception {
		long mine = api.yearly(1L, "내 목표");
		api.expect(api.post("/api/goals/monthly", 2L, "{\"yearlyGoalId\":" + mine + ",\"title\":\"x\"}"), 422,
				"$.code", "GOAL_PARENT_UNAVAILABLE",
				"$.details.parentLevel", "YEARLY",
				"$.details.parentId", (int) mine);
		api.expect(api.post("/api/goals/monthly", 1L, "{\"yearlyGoalId\":999999,\"title\":\"x\"}"), 422,
				"$.code", "GOAL_PARENT_UNAVAILABLE");
	}

	@Test
	void otherUsersMonthlyIs404() throws Exception {
		long monthly = api.monthly(1L, api.yearly(1L, "내 목표"), "월간");
		api.expect(api.get("/api/goals/monthly/" + monthly, 2L), 404,
				"$.code", "RESOURCE_NOT_FOUND",
				"$.details.resource", "MONTHLY_GOAL",
				"$.details.id", (int) monthly);
		api.expect(api.patch("/api/goals/monthly/" + monthly, 2L, "{\"title\":\"탈취\",\"version\":0}"), 404,
				"$.code", "RESOURCE_NOT_FOUND");
		api.expect(api.get("/api/goals/monthly?yearlyGoalId=" + 1, 2L), 200, "$.data.length()", 0);
	}

	/** 조상(연간 목표)이 삭제되면 월간 목표는 그 자체의 deleted_at이 없어도 숨겨진다. 삭제 API는 단위 6이라 DB를 직접 고친다. */
	@Test
	void hiddenByDeletedAncestor() throws Exception {
		long yearly = api.yearly(1L, "연간");
		long monthly = api.monthly(1L, yearly, "월간");
		jdbc.update("UPDATE yearly_goals SET deleted_at = CURRENT_TIMESTAMP WHERE id = ?", yearly);

		api.expect(api.get("/api/goals/monthly/" + monthly, 1L), 404, "$.details.resource", "MONTHLY_GOAL");
		api.expect(api.patch("/api/goals/monthly/" + monthly, 1L, "{\"title\":\"x\",\"version\":0}"), 404);
		api.expect(api.get("/api/goals/monthly", 1L), 200, "$.data.length()", 0);
		api.expect(api.post("/api/goals/monthly", 1L, "{\"yearlyGoalId\":" + yearly + ",\"title\":\"새\"}"), 422,
				"$.code", "GOAL_PARENT_UNAVAILABLE");
	}

	@Test
	void listDefaultsToCurrentMonthAndFiltersByYearly() throws Exception {
		long a = api.yearly(1L, "A");
		long b = api.yearly(1L, "B");
		long ma = api.monthly(1L, a, "A의 월간");
		long mb = api.monthly(1L, b, "B의 월간");
		long october = GoalTestSupport.seedMonthly(jdbc, a, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31),
				"10월");

		api.expect(api.get("/api/goals/monthly", 1L), 200, "$.data.length()", 2);
		api.expect(api.get("/api/goals/monthly?yearlyGoalId=" + b, 1L), 200,
				"$.data.length()", 1, "$.data[0].id", (int) mb);
		api.expect(api.get("/api/goals/monthly?month=2026-10", 1L), 200,
				"$.data.length()", 1, "$.data[0].id", (int) october, "$.data[0].month", "2026-10");
		api.expect(api.get("/api/goals/monthly?month=2026-10&yearlyGoalId=" + b, 1L), 200, "$.data.length()", 0);
		api.expect(api.get("/api/goals/monthly?month=abc", 1L), 400, "$.code", "VALIDATION_FAILED",
				"$.details.fieldErrors[0].field", "month");
		assertThat(ma).isNotEqualTo(mb);
	}

	@Test
	void listOrderFollowsYearlyDisplayOrder() throws Exception {
		long a = api.yearly(1L, "A");
		long b = api.yearly(1L, "B");
		// B의 월간을 먼저 만들어 id는 B쪽이 작지만, 연간 목표 순서(A가 먼저)가 월간 id보다 앞선다.
		long mb = api.monthly(1L, b, "B 월간");
		long ma = api.monthly(1L, a, "A 월간");
		api.expect(api.get("/api/goals/monthly", 1L), 200, "$.data[0].id", (int) ma, "$.data[1].id", (int) mb);

		api.expect(api.put("/api/goals/yearly/order", 1L, "{\"year\":2026,\"goalIds\":[" + b + "," + a + "]}"), 200);
		api.expect(api.get("/api/goals/monthly", 1L), 200, "$.data[0].id", (int) mb, "$.data[1].id", (int) ma);
	}

	@Test
	void patchChangesOnlySentFields() throws Exception {
		long monthly = api.monthly(1L, api.yearly(1L, "연간"), "월간");
		api.expect(api.patch("/api/goals/monthly/" + monthly, 1L,
				"{\"description\":\"설명\",\"color\":\"#ff0000\",\"version\":0}"), 200,
				"$.data.title", "월간", "$.data.description", "설명", "$.data.color", "#FF0000",
				"$.data.displayColor", "#FF0000", "$.data.version", 1);
		api.expect(api.patch("/api/goals/monthly/" + monthly, 1L, "{\"title\":null,\"version\":1}"), 400,
				"$.details.fieldErrors[0].field", "title");
	}

	@Test
	void patchColorNullInheritsParent() throws Exception {
		long monthly = api.monthly(1L, api.yearly(1L, "연간"), "월간");
		api.expect(api.patch("/api/goals/monthly/" + monthly, 1L, "{\"color\":\"#00ff00\",\"version\":0}"), 200,
				"$.data.color", "#00FF00");
		api.expect(api.patch("/api/goals/monthly/" + monthly, 1L, "{\"color\":null,\"version\":1}"), 200,
				"$.data.color", null, "$.data.displayColor", "#3B82F6", "$.data.version", 2);
	}

	@Test
	void patchStaleVersionIs409() throws Exception {
		long monthly = api.monthly(1L, api.yearly(1L, "연간"), "월간");
		api.expect(api.patch("/api/goals/monthly/" + monthly, 1L, "{\"title\":\"첫 수정\",\"version\":0}"), 200,
				"$.data.version", 1);
		api.expect(api.patch("/api/goals/monthly/" + monthly, 1L, "{\"title\":\"오래된 화면\",\"version\":0}"), 409,
				"$.code", "VERSION_CONFLICT", "$.details.currentVersion", 1);
		api.expect(api.patch("/api/goals/monthly/" + monthly, 1L, "{\"title\":\"버전 없음\"}"), 400,
				"$.details.fieldErrors[0].field", "version");
	}

	@Test
	void onHoldParentGivesPathStatus() throws Exception {
		long yearly = api.yearly(1L, "연간");
		long monthly = api.monthly(1L, yearly, "월간");
		api.expect(api.patch("/api/goals/yearly/" + yearly, 1L, "{\"status\":\"ON_HOLD\",\"version\":0}"), 200);
		// 자신의 상태는 그대로이고, 상위 목표의 보류가 pathStatus로 전해진다(숨기지 않는다).
		api.expect(api.get("/api/goals/monthly/" + monthly, 1L), 200,
				"$.data.status", "PROGRESS", "$.data.pathStatus", "ON_HOLD");
		api.expect(api.patch("/api/goals/monthly/" + monthly, 1L, "{\"status\":\"DROPPED\",\"version\":0}"), 200,
				"$.data.pathStatus", "DROPPED");
	}

	@Test
	void pastMonthNotCompletedIsDelayed() throws Exception {
		long yearly = api.yearly(1L, "연간");
		long october = GoalTestSupport.seedMonthly(jdbc, yearly, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31),
				"10월");
		api.expect(api.get("/api/goals/monthly/" + october, 1L), 200, "$.data.isDelayed", true);
		jdbc.update("UPDATE monthly_goals SET status = 'COMPLETED' WHERE id = ?", october);
		api.expect(api.get("/api/goals/monthly/" + october, 1L), 200, "$.data.isDelayed", false);
	}

	@Test
	void monthlyDetailHasWeeklyGoalsAndCount() throws Exception {
		long monthly = api.monthly(1L, api.yearly(1L, "연간"), "월간");
		long weekly = api.weekly(1L, monthly, "주간");
		api.expect(api.get("/api/goals/monthly/" + monthly, 1L), 200,
				"$.data.weeklyGoalCount", 1,
				"$.data.progress.state", "RECORDED",
				"$.data.weeklyGoals[0].id", (int) weekly,
				"$.data.weeklyGoals[0].path.monthlyGoal.month", "2026-11");
	}

	@Test
	void yearlyDetailIncludesMonthlyWithWeeklies() throws Exception {
		long yearly = api.yearly(1L, "연간");
		long november = api.monthly(1L, yearly, "11월");
		long october = GoalTestSupport.seedMonthly(jdbc, yearly, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31),
				"10월");
		long hiddenWeekly = GoalTestSupport.seedWeekly(jdbc, november, LocalDate.of(2026, 11, 1),
				LocalDate.of(2026, 11, 7), "지운 주간", "CHECKLIST", null);
		jdbc.update("UPDATE weekly_goals SET deleted_at = CURRENT_TIMESTAMP WHERE id = ?", hiddenWeekly);
		long w2 = GoalTestSupport.seedWeekly(jdbc, november, LocalDate.of(2026, 11, 8), LocalDate.of(2026, 11, 14),
				"둘째 주", "CHECKLIST", null);
		long w1 = GoalTestSupport.seedWeekly(jdbc, november, LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 7),
				"첫째 주", "CHECKLIST", null);
		jdbc.update("UPDATE monthly_goals SET deleted_at = CURRENT_TIMESTAMP WHERE id = ?",
				GoalTestSupport.seedMonthly(jdbc, yearly, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30), "9월"));

		// 월간은 시작일 순(10월, 11월), 주간도 시작일 순이고 삭제된 하위 목표는 빠진다.
		api.expect(api.get("/api/goals/yearly/" + yearly, 1L), 200,
				"$.data.goal.id", (int) yearly,
				"$.data.monthlyGoals.length()", 2,
				"$.data.monthlyGoals[0].id", (int) october,
				"$.data.monthlyGoals[0].weeklyGoals.length()", 0,
				"$.data.monthlyGoals[1].id", (int) november,
				"$.data.monthlyGoals[1].weeklyGoalCount", 2,
				"$.data.monthlyGoals[1].weeklyGoals[0].id", (int) w1,
				"$.data.monthlyGoals[1].weeklyGoals[1].id", (int) w2);
	}

	@Test
	void progressLogTitleForMonthly() throws Exception {
		long yearly = api.yearly(1L, "연간");
		long monthly = api.monthly(1L, yearly, "월간 제목");
		long weekly = GoalTestSupport.seedWeekly(jdbc, monthly, LocalDate.of(2026, 11, 8), LocalDate.of(2026, 11, 14),
				"주간 제목", "CHECKLIST", null);
		insertLog(1L, "MONTHLY_GOAL", monthly);
		insertLog(1L, "WEEKLY_GOAL", weekly);

		MvcResult result = api.get("/api/goals/progress-logs", 1L);
		api.expect(result, 200, "$.data.length()", 2);
		String body = api.body(result);
		assertThat(body).contains("\"targetTitle\":\"월간 제목\"").contains("\"targetTitle\":\"주간 제목\"");
	}

	private void insertLog(long userId, String type, long targetId) {
		jdbc.update("INSERT INTO goal_progress_logs (user_id, target_type, target_id, previous_rate, new_rate, "
				+ "log_date) VALUES (?, ?, ?, ?, ?, ?)", userId, type, targetId, new BigDecimal("0.00"),
				new BigDecimal("10.00"), java.sql.Date.valueOf(LocalDate.of(2026, 11, 12)));
	}
}
