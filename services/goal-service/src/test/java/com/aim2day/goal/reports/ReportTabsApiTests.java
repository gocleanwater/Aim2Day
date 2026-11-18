package com.aim2day.goal.reports;

import static org.assertj.core.api.Assertions.assertThat;

import com.aim2day.goal.support.GoalTestSupport;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

/** GET /api/reports/tabs: 월간은 항상, 3·6·9·12월은 분기, 12월은 연간(결정 20). */
class ReportTabsApiTests extends ReportTestBase {

	@Test
	void 월을_생략하면_이번_달_월간_탭만_있다() throws Exception {
		MvcResult r = api.get("/api/reports/tabs", 1L);
		api.expect(r, 200, "$.data.month", "2026-11", "$.data.tabs.length()", 1, "$.data.tabs[0].periodType", "MONTHLY",
				"$.data.tabs[0].period", "2026-11", "$.data.tabs[0].label", "2026.11 월간",
				"$.data.tabs[0].startDate", "2026-11-01", "$.data.tabs[0].endDate", "2026-11-30");
	}

	@Test
	void 진행_중인_십이월은_월간_탭만_있다() throws Exception {
		setNow("2026-12-15T03:00:00Z");
		MvcResult r = api.get("/api/reports/tabs?month=2026-12", 1L);
		api.expect(r, 200, "$.data.month", "2026-12", "$.data.tabs.length()", 1, "$.data.tabs[0].label", "2026.12 월간");
	}

	@Test
	void 연말이_지나면_십이월은_월간_분기_연간_세_탭을_순서대로_준다() throws Exception {
		setNow("2027-01-05T03:00:00Z");
		MvcResult r = api.get("/api/reports/tabs?month=2026-12", 1L);
		api.expect(r, 200, "$.data.month", "2026-12", "$.data.tabs.length()", 3,
				"$.data.tabs[0].label", "2026.12 월간",
				"$.data.tabs[1].periodType", "QUARTERLY", "$.data.tabs[1].period", "2026-Q4",
				"$.data.tabs[1].label", "2026 4분기", "$.data.tabs[1].startDate", "2026-10-01",
				"$.data.tabs[1].endDate", "2026-12-31",
				"$.data.tabs[2].periodType", "YEARLY", "$.data.tabs[2].period", "2026",
				"$.data.tabs[2].label", "2026년 연간", "$.data.tabs[2].startDate", "2026-01-01",
				"$.data.tabs[2].endDate", "2026-12-31");
	}

	@Test
	void 분기_탭은_분기_마지막_날_당일까지_숨기고_다음_날부터_보인다() throws Exception {
		setNow("2026-06-15T03:00:00Z");
		api.expect(api.get("/api/reports/tabs?month=2026-06", 1L), 200, "$.data.tabs.length()", 1);
		setNow("2026-06-30T03:00:00Z");
		api.expect(api.get("/api/reports/tabs?month=2026-06", 1L), 200, "$.data.tabs.length()", 1);
		setNow("2026-07-01T03:00:00Z");
		api.expect(api.get("/api/reports/tabs?month=2026-06", 1L), 200, "$.data.tabs.length()", 2);
		// 리포트 자체는 진행 중인 분기도 계산해 준다(탭만 안 보인다).
		setNow("2026-06-15T03:00:00Z");
		api.expect(report(1L, "QUARTERLY", "2026-Q2"), 200);
	}

	@Test
	void 삼_육_구월은_분기_탭이_있고_연간은_없다() throws Exception {
		for (String[] c : new String[][] {{"2026-03", "2026-Q1", "2026 1분기"}, {"2026-06", "2026-Q2", "2026 2분기"},
				{"2026-09", "2026-Q3", "2026 3분기"}}) {
			MvcResult r = api.get("/api/reports/tabs?month=" + c[0], 1L);
			api.expect(r, 200, "$.data.tabs.length()", 2, "$.data.tabs[1].period", c[1], "$.data.tabs[1].label", c[2]);
		}
	}

	@Test
	void 일월_십일월은_월간_탭만_있다() throws Exception {
		for (String month : List.of("2026-01", "2026-11", "2026-02")) {
			api.expect(api.get("/api/reports/tabs?month=" + month, 1L), 200, "$.data.tabs.length()", 1);
		}
	}

	@Test
	void 미래_달은_422() throws Exception {
		api.expect(api.get("/api/reports/tabs?month=2026-12", 1L), 422, "$.code", "REPORT_INVALID_PERIOD");
		// 이번 달은 미래가 아니다.
		api.expect(api.get("/api/reports/tabs?month=2026-11", 1L), 200);
	}

	@Test
	void 월_형식이_틀리면_400() throws Exception {
		for (String bad : List.of("2026-13", "abc", "2026-1", "202611", "2026-00")) {
			assertFieldError(api.get("/api/reports/tabs?month=" + bad, 1L), "month", "INVALID_FORMAT");
		}
	}

	@Test
	void 계정_시간대로_이번_달을_정한다() throws Exception {
		// UTC 11월 30일 16:00은 서울에서 12월 1일이다. 서울 사용자는 12월 탭을 조회할 수 있다.
		setNow("2026-11-30T16:00:00Z");
		api.expect(api.get("/api/reports/tabs?month=2026-12", 1L), 200, "$.data.tabs.length()", 1);
		// 시간대를 UTC로 바꾸면 같은 시각이 아직 11월 30일이라 12월은 미래다.
		api.expect(api.put("/api/settings", 1L,
				"{\"weekStartDay\":\"MONDAY\",\"timezone\":\"UTC\",\"version\":1}"), 200);
		api.expect(api.get("/api/reports/tabs?month=2026-12", 1L), 422);
		assertThat(GoalTestSupport.<String>read(api.get("/api/reports/tabs", 1L), "$.data.month")).isEqualTo("2026-11");
	}

	@Test
	void 로그인하지_않으면_401() throws Exception {
		MvcResult r = mockMvc.perform(
				org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/reports/tabs")).andReturn();
		assertThat(r.getResponse().getStatus()).isEqualTo(401);
	}
}
