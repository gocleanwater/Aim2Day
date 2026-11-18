package com.aim2day.goal.calendar;

import com.aim2day.goal.support.GoalApiClient;
import com.aim2day.goal.support.GoalTestSupport;
import com.aim2day.goal.support.MutableClock;
import com.aim2day.goal.support.TestClockConfig;
import com.aim2day.goal.support.TestSessionConfig;
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
 * 달력 API 테스트 공통 준비. 시계는 2026-11-12T03:00Z(서울 목요일 12:00)이고 계정 시간대는 서울이라 오늘은 2026-11-12다.
 * 사용자 1에게 연간 목표 하나(y1)를 API로 만들어 둔다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestSessionConfig.class, TestClockConfig.class})
abstract class CalendarTestBase {

	@Autowired
	MockMvc mockMvc;
	@Autowired
	MapSessionRepository sessionRepository;
	@Autowired
	JdbcTemplate jdbc;
	@Autowired
	MutableClock clock;

	GoalApiClient api;
	long y1;

	@BeforeEach
	void seed() throws Exception {
		clock.reset();
		GoalTestSupport.clean(jdbc);
		api = new GoalApiClient(mockMvc, sessionRepository);
		// 계정 시간대를 서울로 지정한다(UTC로 두면 같은 시각의 오늘이 11-12로 같지만, 시간대 경계 테스트를 위해 고정).
		api.expect(api.put("/api/settings", 1L, "{\"weekStartDay\":\"MONDAY\",\"timezone\":\"Asia/Seoul\",\"version\":0}"),
				200);
		y1 = api.yearly(1L, "백엔드 개발자 취업 준비");
	}

	/** 달력 항목을 만든다(201 확인). json은 요청 본문 전체다. */
	long item(long userId, String json) throws Exception {
		MvcResult result = api.post("/api/calendar/items", userId, json);
		api.expect(result, 201);
		return GoalTestSupport.idOf(result);
	}

	long task(String title, String date, String extra) throws Exception {
		return item(1L, "{\"itemType\":\"TASK\",\"title\":\"" + title + "\",\"startDate\":\"" + date + "\"" + extra + "}");
	}

	long event(String title, String start, String end, String extra) throws Exception {
		return item(1L, "{\"itemType\":\"EVENT\",\"title\":\"" + title + "\",\"startDate\":\"" + start + "\""
				+ (end == null ? "" : ",\"endDate\":\"" + end + "\"") + extra + "}");
	}

	String completionPath(long id) {
		return "/api/calendar/items/" + id + "/completion";
	}

	int count(String sql, Object... args) {
		return jdbc.queryForObject(sql, Integer.class, args);
	}
}
