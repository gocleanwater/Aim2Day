package com.aim2day.goal.reports;

import static org.assertj.core.api.Assertions.assertThat;

import com.aim2day.goal.support.GoalTestSupport;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

/** GET /api/reports: 집계 대상, 기간 경계, 목표별 달성률 변화 상태, 라벨. 오늘은 2026-11-12(서울)다. */
class ReportApiTests extends ReportTestBase {

	long y1;
	long m1;
	long w1;

	@BeforeEach
	void tree() {
		y1 = yearly(1L, 2026, "백엔드 개발자 취업 준비", 1);
		m1 = GoalTestSupport.seedMonthly(jdbc, y1, LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30), "11월 목표");
		w1 = GoalTestSupport.seedWeekly(jdbc, m1, LocalDate.of(2026, 11, 9), LocalDate.of(2026, 11, 15), "이번 주",
				"CHECKLIST", null);
	}

	private long done(long userId, Long weeklyId, String title, String date, String result) {
		long id = GoalTestSupport.seedAction(jdbc, userId, weeklyId, title, LocalDate.parse(date), true, false);
		if (result != null) {
			jdbc.update("UPDATE actions SET result_summary = ? WHERE id = ?", result, id);
		}
		return id;
	}

	// ---- 집계 대상과 기간 경계 ----

	@Test
	void 기록이_없으면_0과_빈_배열() throws Exception {
		MvcResult r = report(2L, "MONTHLY", "2026-11");
		api.expect(r, 200, "$.data.periodType", "MONTHLY", "$.data.period", "2026-11", "$.data.startDate",
				"2026-11-01", "$.data.endDate", "2026-11-30", "$.data.summary.completedActionCount", 0,
				"$.data.summary.activeDayCount", 0, "$.data.actions.length()", 0, "$.data.results.length()", 0,
				"$.data.goalProgress.length()", 0, "$.data.review", null);
	}

	@Test
	void 종료된_기간은_첫날과_마지막날을_포함하고_전날과_다음날은_제외한다() throws Exception {
		done(1L, w1, "전날", "2026-09-30", null);
		long first = done(1L, w1, "첫날", "2026-10-01", null);
		long last = done(1L, w1, "마지막날", "2026-10-31", null);
		done(1L, w1, "다음날", "2026-11-01", null);
		MvcResult r = report(1L, "MONTHLY", "2026-10");
		api.expect(r, 200, "$.data.summary.completedActionCount", 2, "$.data.actions[0].id", (int) first,
				"$.data.actions[1].id", (int) last);
		api.expect(report(1L, "QUARTERLY", "2026-Q3"), 200, "$.data.summary.completedActionCount", 1);
	}

	@Test
	void 진행_중_기간은_오늘까지만_세고_내일_이후는_제외한다() throws Exception {
		done(1L, w1, "전날", "2026-10-31", null);
		long first = done(1L, w1, "첫날", "2026-11-01", null);
		long today = done(1L, w1, "오늘", "2026-11-12", null);
		done(1L, w1, "내일", "2026-11-13", null);
		done(1L, w1, "말일", "2026-11-30", null);
		api.expect(report(1L, "MONTHLY", "2026-11"), 200, "$.data.summary.completedActionCount", 2,
				"$.data.actions[0].id", (int) first, "$.data.actions[1].id", (int) today);
		// 분기는 10월 31일(전날)부터 들어오고 내일 이후는 같은 규칙으로 빠진다.
		api.expect(report(1L, "QUARTERLY", "2026-Q4"), 200, "$.data.summary.completedActionCount", 3);
	}

	@Test
	void 기간_마지막_날_당일은_진행_중이고_다음_날부터_끝난_기간이다() throws Exception {
		long g = yearly(1L, 2026, "목표", 2);
		log(1L, g, 0, 10, "PROGRESS_UPDATE", "2026-10-01");
		setNow("2026-10-31T03:00:00Z");
		assertThat(this.<String>progress(report(1L, "MONTHLY", "2026-10"), g, "endLabel")).isEqualTo("오늘 (10.31)");
		setNow("2026-11-01T03:00:00Z");
		assertThat(this.<String>progress(report(1L, "MONTHLY", "2026-10"), g, "endLabel")).isEqualTo("10월 말");
	}

	@Test
	void 시간대_변경으로_기록_날짜가_역전돼도_일어난_순서로_기준선을_잡는다() throws Exception {
		// 11/18에 10%로 확정한 뒤 시간대를 바꿔 11/17 날짜로 50%를 기록했다. 날짜순이면 50%가 최초 기준선이 된다.
		setNow("2026-11-20T03:00:00Z");
		long g = yearly(1L, 2026, "역전", 2);
		log(1L, g, 0, 10, "PROGRESS_UPDATE", "2026-11-18");
		log(1L, g, 10, 50, "PROGRESS_UPDATE", "2026-11-17");
		MvcResult r = report(1L, "MONTHLY", "2026-11");
		assertThat(this.<String>progress(r, g, "startBase")).isEqualTo("INITIAL_BASELINE");
		assertThat(this.<Number>progress(r, g, "startRate").doubleValue()).isEqualTo(10.0);
		assertThat(this.<Number>progress(r, g, "endRate").doubleValue()).isEqualTo(50.0);
		assertThat(this.<Number>progress(r, g, "changePp").doubleValue()).isEqualTo(40.0);
		assertThat(this.<String>progress(r, g, "observedFrom")).isEqualTo("2026-11-18");
		assertThat(this.<String>progress(r, g, "startLabel")).isEqualTo("기록 시작 (11.18)");
	}

	@Test
	void 역전된_기록이_기간_경계를_넘으면_앞_기록의_날짜로_본다() throws Exception {
		// 11월 1일 확정(10%), 11월 3일 20%, 그 뒤 시간대 변경으로 10월 30일 날짜의 개정(30%)이 쓰였다.
		// 유효 날짜는 11월 3일이므로 10월 리포트에는 보이지 않고 11월 리포트에는 기간 안 개정으로 잡힌다.
		long g = yearly(1L, 2026, "경계", 2);
		log(1L, g, 0, 10, "PROGRESS_UPDATE", "2026-10-05");
		log(1L, g, 10, 20, "PROGRESS_UPDATE", "2026-11-03");
		log(1L, g, 20, 30, "CRITERIA_REVISION", "2026-10-30");
		MvcResult oct = report(1L, "MONTHLY", "2026-10");
		assertThat(this.<String>progress(oct, g, "state")).isEqualTo("COMPARABLE");
		assertThat(this.<Number>progress(oct, g, "endRate").doubleValue()).isEqualTo(10.0);
		MvcResult nov = report(1L, "MONTHLY", "2026-11");
		assertThat(this.<String>progress(nov, g, "state")).isEqualTo("CRITERIA_REVISED");
		assertThat(this.<String>progress(nov, g, "startBase")).isEqualTo("PREVIOUS_PERIOD_END");
		assertThat(this.<Number>progress(nov, g, "startRate").doubleValue()).isEqualTo(10.0);
		assertThat(this.<Number>progress(nov, g, "endRate").doubleValue()).isEqualTo(30.0);
	}

	@Test
	void 집계_포함과_제외_규칙() throws Exception {
		long a1 = done(1L, w1, "포함 첫날 결과 있음", "2026-11-01", "블로그 정리");
		long a2 = done(1L, w1, "포함 같은 날 결과 없음", "2026-11-01", null);
		// 사용자가 지운 완료 행동은 포함한다(결정 7). 결과가 공백뿐이면 results에는 넣지 않는다.
		long a3 = GoalTestSupport.seedAction(jdbc, 1L, w1, "지운 완료 행동", LocalDate.of(2026, 11, 5), true, true);
		jdbc.update("UPDATE actions SET result_summary = '   ' WHERE id = ?", a3);
		// 삭제된 목표에 연결된 완료 행동도 포함하고, 이름은 현재 이름이다.
		long w2 = GoalTestSupport.seedWeekly(jdbc, m1, LocalDate.of(2026, 11, 16), LocalDate.of(2026, 11, 22), "지운 주간",
				"CHECKLIST", null);
		long a4 = done(1L, w2, "삭제된 주간의 행동", "2026-11-10", "초안 작성");
		jdbc.update("UPDATE weekly_goals SET deleted_at = CURRENT_TIMESTAMP WHERE id = ?", w2);
		// 제외: 미완료, MAINTENANCE, 다른 사용자.
		GoalTestSupport.seedAction(jdbc, 1L, w1, "미완료", LocalDate.of(2026, 11, 3), false, false);
		done(1L, null, "유지 행동", "2026-11-03", "무시");
		long w3 = GoalTestSupport.seedWeekly(jdbc, GoalTestSupport.seedMonthly(jdbc, yearly(2L, 2026, "남의 목표", 1),
				LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30), "남의 월간"), LocalDate.of(2026, 11, 9),
				LocalDate.of(2026, 11, 15), "남의 주간", "CHECKLIST", null);
		done(2L, w3, "남의 행동", "2026-11-03", "남의 결과");

		MvcResult r = report(1L, "MONTHLY", "2026-11");
		api.expect(r, 200, "$.data.summary.completedActionCount", 4, "$.data.summary.activeDayCount", 3,
				// 수행일 오름차순, 같은 날은 id 오름차순.
				"$.data.actions[0].id", (int) a1, "$.data.actions[1].id", (int) a2, "$.data.actions[2].id", (int) a3,
				"$.data.actions[3].id", (int) a4, "$.data.actions[0].performedDate", "2026-11-01",
				"$.data.actions[0].resultSummary", "블로그 정리", "$.data.actions[1].resultSummary", null,
				"$.data.actions[0].title", "포함 첫날 결과 있음",
				"$.data.results.length()", 2, "$.data.results[0].actionId", (int) a1, "$.data.results[0].performedDate",
				"2026-11-01", "$.data.results[0].resultSummary", "블로그 정리", "$.data.results[1].actionId", (int) a4,
				"$.data.results[1].resultSummary", "초안 작성");
		// goalPath: 행동 응답과 같은 모양(연간·월간·주간). 삭제된 주간도 이름이 보인다.
		api.expect(r, 200, "$.data.actions[0].goalPath.yearlyGoal.id", (int) y1,
				"$.data.actions[0].goalPath.yearlyGoal.title", "백엔드 개발자 취업 준비",
				"$.data.actions[0].goalPath.monthlyGoal.id", (int) m1, "$.data.actions[0].goalPath.monthlyGoal.title",
				"11월 목표", "$.data.actions[0].goalPath.monthlyGoal.month", "2026-11",
				"$.data.actions[0].goalPath.weeklyGoal.id", (int) w1, "$.data.actions[0].goalPath.weeklyGoal.title",
				"이번 주", "$.data.actions[3].goalPath.weeklyGoal.title", "지운 주간");
	}

	@Test
	void goalPath는_목표의_현재_이름이다() throws Exception {
		done(1L, w1, "행동", "2026-11-02", null);
		jdbc.update("UPDATE yearly_goals SET title = '바뀐 이름' WHERE id = ?", y1);
		api.expect(report(1L, "MONTHLY", "2026-11"), 200, "$.data.actions[0].goalPath.yearlyGoal.title", "바뀐 이름");
	}

	@Test
	void 연간_리포트는_연도_전체를_센다() throws Exception {
		done(1L, w1, "연초", "2026-01-01", null);
		done(1L, w1, "오늘", "2026-11-12", null);
		done(1L, w1, "내일", "2026-11-13", null);
		done(1L, w1, "작년", "2025-12-31", null);
		api.expect(report(1L, "YEARLY", "2026"), 200, "$.data.startDate", "2026-01-01", "$.data.endDate", "2026-12-31",
				"$.data.summary.completedActionCount", 2);
	}

	@Test
	void 미래_기간은_422() throws Exception {
		api.expect(report(1L, "MONTHLY", "2026-12"), 422, "$.code", "REPORT_INVALID_PERIOD");
		api.expect(report(1L, "QUARTERLY", "2027-Q1"), 422, "$.code", "REPORT_INVALID_PERIOD");
		api.expect(report(1L, "YEARLY", "2027"), 422, "$.code", "REPORT_INVALID_PERIOD");
		// 진행 중인 기간은 조회할 수 있다.
		api.expect(report(1L, "QUARTERLY", "2026-Q4"), 200);
	}

	@Test
	void 형식이_틀리면_400() throws Exception {
		assertFieldError(report(1L, "WEEKLY", "2026-11"), "periodType", "INVALID_FORMAT");
		assertFieldError(report(1L, "MONTHLY", "2026-Q4"), "period", "INVALID_FORMAT");
		assertFieldError(report(1L, "QUARTERLY", "2026-Q0"), "period", "INVALID_FORMAT");
		assertFieldError(report(1L, "YEARLY", "2026-11"), "period", "INVALID_FORMAT");
		assertFieldError(api.get("/api/reports?period=2026-11", 1L), "periodType", "REQUIRED");
		assertFieldError(api.get("/api/reports?periodType=MONTHLY", 1L), "period", "REQUIRED");
	}

	@Test
	void 계정_날짜가_바뀌면_기간_허용과_오늘_라벨이_따라간다() throws Exception {
		long g = yearly(1L, 2026, "목표", 2);
		log(1L, g, 0, 10, "PROGRESS_UPDATE", "2026-11-05");
		// UTC 11월 30일 16:00 = 서울 12월 1일 01:00. 12월 리포트가 열리고 끝 라벨은 오늘이다.
		setNow("2026-11-30T16:00:00Z");
		api.expect(report(1L, "MONTHLY", "2026-12"), 200);
		// 11월 리포트는 끝난 기간이라 "11월 말"이다.
		MvcResult nov = report(1L, "MONTHLY", "2026-11");
		assertThat(this.<String>progress(nov, g, "endLabel")).isEqualTo("11월 말");
		MvcResult dec = report(1L, "MONTHLY", "2026-12");
		assertThat(this.<String>progress(dec, g, "endLabel")).isEqualTo("오늘 (12.01)");
	}

	// ---- 목표별 달성률 변화 ----

	@Test
	void 달성률_변화_상태와_시작_기준_여섯_경우() throws Exception {
		// 이미 만들어 둔 y1은 기준을 확정하지 않은 목표다(기록 없음).
		// 1) 기간 전 확정: 10월에 기준선, 11월에 두 번 변경(같은 날 두 건은 나중 기록이 마지막 값).
		long g1 = yearly(1L, 2026, "기간 전 확정", 2);
		log(1L, g1, 0, 20, "PROGRESS_UPDATE", "2026-10-15");
		log(1L, g1, 20, 35, "PROGRESS_UPDATE", "2026-11-05");
		log(1L, g1, 35, 38, "PROGRESS_UPDATE", "2026-11-05");
		// 2) 기간 중 최초 확정
		long g2 = yearly(1L, 2026, "기간 중 최초 확정", 3);
		log(1L, g2, 0, 10, "PROGRESS_UPDATE", "2026-11-05");
		log(1L, g2, 10, 30, "PROGRESS_UPDATE", "2026-11-10");
		// 3) 기간 중 개정 (그리고 4) 기간 뒤 개정이라 지난 리포트는 비교 가능)
		long g3 = yearly(1L, 2026, "기간 중 개정", 4);
		log(1L, g3, 0, 20, "PROGRESS_UPDATE", "2026-09-20");
		log(1L, g3, 20, 25, "CRITERIA_REVISION", "2026-11-08");
		// 5) 소프트 삭제된 목표도 영구 삭제 전이면 나온다.
		long g5 = yearly(1L, 2026, "삭제된 목표", 5);
		log(1L, g5, 0, 5, "PROGRESS_UPDATE", "2026-10-01");
		jdbc.update("UPDATE yearly_goals SET deleted_at = CURRENT_TIMESTAMP WHERE id = ?", g5);
		// 제외: 다른 해, 다른 사용자.
		long other = yearly(1L, 2025, "작년 목표", 6);
		log(1L, other, 0, 50, "PROGRESS_UPDATE", "2025-10-01");
		long theirs = yearly(2L, 2026, "남의 목표", 1);
		log(2L, theirs, 0, 70, "PROGRESS_UPDATE", "2026-10-01");

		MvcResult nov = report(1L, "MONTHLY", "2026-11");
		api.expect(nov, 200, "$.data.goalProgress.length()", 5, "$.data.goalProgress[0].yearlyGoalId", (int) y1,
				"$.data.goalProgress[1].yearlyGoalId", (int) g1, "$.data.goalProgress[2].yearlyGoalId", (int) g2,
				"$.data.goalProgress[3].yearlyGoalId", (int) g3, "$.data.goalProgress[4].yearlyGoalId", (int) g5);

		// 미확정
		api.expect(nov, 200, "$.data.goalProgress[0].state", "NOT_CONFIGURED", "$.data.goalProgress[0].startBase", null,
				"$.data.goalProgress[0].startRate", null, "$.data.goalProgress[0].endRate", null,
				"$.data.goalProgress[0].changePp", null, "$.data.goalProgress[0].startLabel", null,
				"$.data.goalProgress[0].endLabel", null, "$.data.goalProgress[0].observedFrom", null,
				"$.data.goalProgress[0].note", "달성 기준 설정 전", "$.data.goalProgress[0].title", "백엔드 개발자 취업 준비");
		assertThat(this.<String>progress(nov, y1, "color")).isEqualTo("#3B82F6");
		// 기간 전 확정
		api.expect(nov, 200, "$.data.goalProgress[1].state", "COMPARABLE", "$.data.goalProgress[1].startBase",
				"PREVIOUS_PERIOD_END", "$.data.goalProgress[1].startLabel", "10월 말", "$.data.goalProgress[1].endLabel",
				"오늘 (11.12)", "$.data.goalProgress[1].startRate", 20.0, "$.data.goalProgress[1].endRate", 38.0,
				"$.data.goalProgress[1].changePp", 18.0, "$.data.goalProgress[1].observedFrom", "2026-11-01",
				"$.data.goalProgress[1].note", null);
		// 기간 중 최초 확정
		api.expect(nov, 200, "$.data.goalProgress[2].state", "COMPARABLE", "$.data.goalProgress[2].startBase",
				"INITIAL_BASELINE", "$.data.goalProgress[2].startLabel", "기록 시작 (11.05)",
				"$.data.goalProgress[2].startRate", 10.0, "$.data.goalProgress[2].endRate", 30.0,
				"$.data.goalProgress[2].changePp", 20.0, "$.data.goalProgress[2].observedFrom", "2026-11-05",
				"$.data.goalProgress[2].note", "11.05 기준 확정 이후 변화");
		// 기간 중 개정: changePp만 비우고 나머지는 채운다.
		api.expect(nov, 200, "$.data.goalProgress[3].state", "CRITERIA_REVISED", "$.data.goalProgress[3].changePp", null,
				"$.data.goalProgress[3].note", "기준·배점 변경으로 비교 불가", "$.data.goalProgress[3].startRate", 20.0,
				"$.data.goalProgress[3].endRate", 25.0, "$.data.goalProgress[3].startBase", "PREVIOUS_PERIOD_END");
		// 소프트 삭제
		api.expect(nov, 200, "$.data.goalProgress[4].title", "삭제된 목표", "$.data.goalProgress[4].state", "COMPARABLE",
				"$.data.goalProgress[4].startRate", 5.0, "$.data.goalProgress[4].endRate", 5.0,
				"$.data.goalProgress[4].changePp", 0.0);

		// 10월 리포트: 11월의 개정은 기간 뒤라 g3는 그대로 비교 가능하다. g2는 10월 말까지 기록이 없어 미확정이다.
		MvcResult oct = report(1L, "MONTHLY", "2026-10");
		assertThat(this.<String>progress(oct, g3, "state")).isEqualTo("COMPARABLE");
		assertThat(this.<String>progress(oct, g3, "startLabel")).isEqualTo("9월 말");
		assertThat(this.<String>progress(oct, g3, "endLabel")).isEqualTo("10월 말");
		assertThat(this.<Number>progress(oct, g3, "endRate").doubleValue()).isEqualTo(20.0);
		assertThat(this.<Number>progress(oct, g3, "changePp").doubleValue()).isEqualTo(0.0);
		assertThat(this.<String>progress(oct, g2, "state")).isEqualTo("NOT_CONFIGURED");
		assertThat(this.<String>progress(oct, g1, "startBase")).isEqualTo("INITIAL_BASELINE");
		assertThat(this.<String>progress(oct, g1, "startLabel")).isEqualTo("기록 시작 (10.15)");
		assertThat(this.<Number>progress(oct, g1, "changePp").doubleValue()).isEqualTo(0.0);
	}

	@Test
	void 소수_둘째_자리_값은_그대로_나온다() throws Exception {
		long g = yearly(1L, 2026, "소수", 2);
		jdbc.update("INSERT INTO goal_progress_logs (user_id, target_type, target_id, previous_rate, new_rate, "
				+ "change_type, log_date) VALUES (1, 'YEARLY_GOAL', ?, 0, 33.33, 'PROGRESS_UPDATE', '2026-10-01')", g);
		jdbc.update("INSERT INTO goal_progress_logs (user_id, target_type, target_id, previous_rate, new_rate, "
				+ "change_type, log_date) VALUES (1, 'YEARLY_GOAL', ?, 33.33, 66.67, 'PROGRESS_UPDATE', '2026-11-02')", g);
		MvcResult r = report(1L, "MONTHLY", "2026-11");
		assertThat(new BigDecimal(this.<Number>progress(r, g, "changePp").toString()).doubleValue()).isEqualTo(33.34);
		assertThat(this.<Number>progress(r, g, "startRate").doubleValue()).isEqualTo(33.33);
	}

	@Test
	void 분기_연간_라벨() throws Exception {
		long g = yearly(1L, 2026, "라벨", 2);
		log(1L, g, 0, 10, "PROGRESS_UPDATE", "2025-12-20");
		log(1L, g, 10, 20, "PROGRESS_UPDATE", "2026-02-01");
		log(1L, g, 20, 30, "PROGRESS_UPDATE", "2026-04-01");
		log(1L, g, 30, 40, "PROGRESS_UPDATE", "2026-10-05");

		MvcResult jan = report(1L, "MONTHLY", "2026-01");
		assertThat(this.<String>progress(jan, g, "startLabel")).isEqualTo("12월 말");
		assertThat(this.<String>progress(jan, g, "endLabel")).isEqualTo("1월 말");
		assertThat(this.<Number>progress(jan, g, "endRate").doubleValue()).isEqualTo(10.0);

		MvcResult q1 = report(1L, "QUARTERLY", "2026-Q1");
		assertThat(this.<String>progress(q1, g, "startLabel")).isEqualTo("4분기 말");
		assertThat(this.<String>progress(q1, g, "endLabel")).isEqualTo("1분기 말");
		assertThat(this.<Number>progress(q1, g, "changePp").doubleValue()).isEqualTo(10.0);

		MvcResult q3 = report(1L, "QUARTERLY", "2026-Q3");
		assertThat(this.<String>progress(q3, g, "startLabel")).isEqualTo("2분기 말");
		assertThat(this.<String>progress(q3, g, "endLabel")).isEqualTo("3분기 말");
		assertThat(this.<Number>progress(q3, g, "changePp").doubleValue()).isEqualTo(0.0);

		MvcResult q4 = report(1L, "QUARTERLY", "2026-Q4");
		assertThat(this.<String>progress(q4, g, "startLabel")).isEqualTo("3분기 말");
		assertThat(this.<String>progress(q4, g, "endLabel")).isEqualTo("오늘 (11.12)");
		assertThat(this.<Number>progress(q4, g, "endRate").doubleValue()).isEqualTo(40.0);

		MvcResult year = report(1L, "YEARLY", "2026");
		assertThat(this.<String>progress(year, g, "startLabel")).isEqualTo("2025년 말");
		assertThat(this.<String>progress(year, g, "endLabel")).isEqualTo("오늘 (11.12)");
		assertThat(this.<Number>progress(year, g, "changePp").doubleValue()).isEqualTo(30.0);

		// 끝난 기간(2025년 리포트는 2025 목표가 없어 비어 있다). 지난 연도의 endLabel 확인은 2025 목표로 한다.
		long old = yearly(1L, 2025, "작년", 3);
		log(1L, old, 0, 10, "PROGRESS_UPDATE", "2025-03-01");
		log(1L, old, 10, 50, "PROGRESS_UPDATE", "2025-12-30");
		MvcResult y2025 = report(1L, "YEARLY", "2025");
		assertThat(this.<String>progress(y2025, old, "endLabel")).isEqualTo("2025년 말");
		assertThat(this.<String>progress(y2025, old, "startBase")).isEqualTo("INITIAL_BASELINE");
		assertThat(this.<String>progress(y2025, old, "startLabel")).isEqualTo("기록 시작 (03.01)");
	}

	@Test
	void 다른_사용자의_데이터는_섞이지_않는다() throws Exception {
		done(1L, w1, "내 행동", "2026-11-02", "내 결과");
		MvcResult r = report(2L, "MONTHLY", "2026-11");
		api.expect(r, 200, "$.data.summary.completedActionCount", 0, "$.data.actions.length()", 0,
				"$.data.goalProgress.length()", 0);
		List<Object> titles = GoalTestSupport.read(report(1L, "MONTHLY", "2026-11"), "$.data.actions[*].title");
		assertThat(titles).containsExactly("내 행동");
	}
}
