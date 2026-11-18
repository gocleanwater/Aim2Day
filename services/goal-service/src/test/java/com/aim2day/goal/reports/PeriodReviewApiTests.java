package com.aim2day.goal.reports;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

/** PUT /api/reports/reviews/{periodType}/{period}: version 규칙 4가지, 길이, 전체 저장, 미래 기간, 사용자별 독립. */
class PeriodReviewApiTests extends ReportTestBase {

	private static final String NOV = "/api/reports/reviews/MONTHLY/2026-11";

	@Test
	void 첫_저장은_version_없이_하고_version_1을_받는다() throws Exception {
		MvcResult r = api.put(NOV, 1L, "{\"improvements\":\"꾸준히 풀기\",\"nextFocus\":\"그래프\"}");
		api.expect(r, 200, "$.data.improvements", "꾸준히 풀기", "$.data.nextFocus", "그래프", "$.data.version", 1);
		assertThat(count("SELECT COUNT(*) FROM period_reviews")).isEqualTo(1);
	}

	@Test
	void 같은_version으로_고치면_version이_오른다() throws Exception {
		api.expect(api.put(NOV, 1L, "{\"improvements\":\"a\",\"nextFocus\":\"b\"}"), 200);
		MvcResult r = api.put(NOV, 1L, "{\"improvements\":\"a2\",\"nextFocus\":\"b2\",\"version\":1}");
		api.expect(r, 200, "$.data.improvements", "a2", "$.data.version", 2);
		api.expect(api.put(NOV, 1L, "{\"improvements\":\"a3\",\"version\":2}"), 200, "$.data.version", 3);
		assertThat(count("SELECT COUNT(*) FROM period_reviews")).isEqualTo(1);
	}

	@Test
	void 있는데_version을_생략하면_409와_현재_version() throws Exception {
		api.expect(api.put(NOV, 1L, "{\"improvements\":\"a\"}"), 200);
		api.expect(api.put(NOV, 1L, "{\"improvements\":\"덮어쓰기\"}"), 409, "$.code", "VERSION_CONFLICT",
				"$.details.currentVersion", 1);
		assertThat(jdbc.queryForObject("SELECT improvements FROM period_reviews", String.class)).isEqualTo("a");
	}

	@Test
	void 있는데_version이_다르면_409와_현재_version() throws Exception {
		api.expect(api.put(NOV, 1L, "{\"improvements\":\"a\"}"), 200);
		api.expect(api.put(NOV, 1L, "{\"improvements\":\"b\",\"version\":1}"), 200);
		api.expect(api.put(NOV, 1L, "{\"improvements\":\"c\",\"version\":1}"), 409, "$.code", "VERSION_CONFLICT",
				"$.details.currentVersion", 2);
		assertThat(jdbc.queryForObject("SELECT improvements FROM period_reviews", String.class)).isEqualTo("b");
	}

	@Test
	void 없는데_version을_보내면_409_currentVersion_null() throws Exception {
		MvcResult r = api.put(NOV, 1L, "{\"improvements\":\"a\",\"version\":3}");
		api.expect(r, 409, "$.code", "VERSION_CONFLICT", "$.details.currentVersion", null);
		assertThat(api.body(r)).contains("\"currentVersion\":null");
		assertThat(count("SELECT COUNT(*) FROM period_reviews")).isEqualTo(0);
	}

	@Test
	void 빠진_필드는_null로_저장한다() throws Exception {
		api.expect(api.put(NOV, 1L, "{\"improvements\":\"a\",\"nextFocus\":\"b\"}"), 200);
		MvcResult r = api.put(NOV, 1L, "{\"improvements\":\"a2\",\"version\":1}");
		api.expect(r, 200, "$.data.improvements", "a2", "$.data.nextFocus", null);
		assertThat(jdbc.queryForObject("SELECT next_focus FROM period_reviews", String.class)).isNull();
		// JSON null도 같다.
		api.expect(api.put(NOV, 1L, "{\"improvements\":null,\"nextFocus\":\"b3\",\"version\":2}"), 200,
				"$.data.improvements", null, "$.data.nextFocus", "b3");
	}

	@Test
	void 두_필드_모두_없는_본문도_저장된다() throws Exception {
		api.expect(api.put(NOV, 1L, "{}"), 200, "$.data.improvements", null, "$.data.nextFocus", null,
				"$.data.version", 1);
	}

	@Test
	void 오천자는_저장되고_오천일자는_400() throws Exception {
		String max = "가".repeat(5000);
		api.expect(api.put(NOV, 1L, "{\"improvements\":\"" + max + "\",\"nextFocus\":\"" + max + "\"}"), 200);
		String over = "가".repeat(5001);
		assertFieldError(api.put(NOV, 1L, "{\"improvements\":\"" + over + "\",\"version\":1}"), "improvements",
				"TOO_LONG");
		assertFieldError(api.put(NOV, 1L, "{\"nextFocus\":\"" + over + "\",\"version\":1}"), "nextFocus", "TOO_LONG");
		// 글자 수는 코드 포인트로 센다. 이모지 5,000개는 UTF-16으로 10,000이지만 허용된다.
		String emoji = "😀".repeat(5000);
		api.expect(api.put(NOV, 1L, "{\"improvements\":\"" + emoji + "\",\"version\":1}"), 200);
		String emojiOver = "😀".repeat(5001);
		assertFieldError(api.put(NOV, 1L, "{\"improvements\":\"" + emojiOver + "\",\"version\":2}"), "improvements",
				"TOO_LONG");
	}

	@Test
	void 서버는_내용을_바꾸지_않는다() throws Exception {
		String text = "  앞뒤 공백과\\n줄바꿈 유지  ";
		MvcResult r = api.put(NOV, 1L, "{\"improvements\":\"" + text + "\"}");
		api.expect(r, 200);
		assertThat(jdbc.queryForObject("SELECT improvements FROM period_reviews", String.class))
				.isEqualTo("  앞뒤 공백과\n줄바꿈 유지  ");
	}

	@Test
	void 미래_기간은_422이고_저장하지_않는다() throws Exception {
		for (String path : List.of("MONTHLY/2026-12", "QUARTERLY/2027-Q1", "YEARLY/2027")) {
			api.expect(api.put("/api/reports/reviews/" + path, 1L, "{\"improvements\":\"a\"}"), 422, "$.code",
					"REPORT_INVALID_PERIOD");
		}
		assertThat(count("SELECT COUNT(*) FROM period_reviews")).isEqualTo(0);
		// 진행 중인 분기·연간은 저장할 수 있다.
		api.expect(api.put("/api/reports/reviews/QUARTERLY/2026-Q4", 1L, "{\"improvements\":\"a\"}"), 200);
		api.expect(api.put("/api/reports/reviews/YEARLY/2026", 1L, "{\"improvements\":\"a\"}"), 200);
	}

	@Test
	void 형식이_틀리면_400() throws Exception {
		assertFieldError(api.put("/api/reports/reviews/WEEKLY/2026-11", 1L, "{}"), "periodType", "INVALID_FORMAT");
		assertFieldError(api.put("/api/reports/reviews/MONTHLY/2026-Q4", 1L, "{}"), "period", "INVALID_FORMAT");
		assertFieldError(api.put("/api/reports/reviews/QUARTERLY/2026-Q5", 1L, "{}"), "period", "INVALID_FORMAT");
		assertFieldError(api.put("/api/reports/reviews/YEARLY/26", 1L, "{}"), "period", "INVALID_FORMAT");
		assertFieldError(api.put("/api/reports/reviews/MONTHLY/2026-13", 1L, "{}"), "period", "INVALID_FORMAT");
	}

	@Test
	void 사용자와_기간마다_따로_저장된다() throws Exception {
		api.expect(api.put(NOV, 1L, "{\"improvements\":\"사용자1 월간\"}"), 200, "$.data.version", 1);
		// 다른 사용자는 같은 기간을 version 없이 처음 저장한다.
		api.expect(api.put(NOV, 2L, "{\"improvements\":\"사용자2 월간\"}"), 200, "$.data.version", 1);
		// 같은 사용자의 다른 기간 유형도 따로다.
		api.expect(api.put("/api/reports/reviews/QUARTERLY/2026-Q4", 1L, "{\"improvements\":\"분기\"}"), 200,
				"$.data.version", 1);
		api.expect(api.put("/api/reports/reviews/MONTHLY/2026-10", 1L, "{\"improvements\":\"10월\"}"), 200,
				"$.data.version", 1);
		assertThat(count("SELECT COUNT(*) FROM period_reviews")).isEqualTo(4);
		assertThat(count("SELECT COUNT(*) FROM period_reviews WHERE user_id = 2")).isEqualTo(1);
		// 사용자 2가 사용자 1의 version(2)으로 보내도 자기 회고만 본다.
		api.expect(api.put(NOV, 2L, "{\"improvements\":\"x\",\"version\":9}"), 409, "$.details.currentVersion", 1);
		assertThat(jdbc.queryForObject("SELECT improvements FROM period_reviews WHERE user_id = 1 "
				+ "AND period_type = 'MONTHLY' AND period_value = '2026-11'", String.class)).isEqualTo("사용자1 월간");
	}

	@Test
	void 리포트_조회에_회고가_실린다() throws Exception {
		api.expect(report(1L, "MONTHLY", "2026-11"), 200, "$.data.review", null);
		api.expect(api.put(NOV, 1L, "{\"improvements\":\"a\",\"nextFocus\":\"b\"}"), 200);
		api.expect(report(1L, "MONTHLY", "2026-11"), 200, "$.data.review.improvements", "a", "$.data.review.nextFocus",
				"b", "$.data.review.version", 1);
		// 다른 사용자와 다른 탭은 보지 못한다.
		api.expect(report(2L, "MONTHLY", "2026-11"), 200, "$.data.review", null);
		api.expect(report(1L, "QUARTERLY", "2026-Q4"), 200, "$.data.review", null);
	}
}
