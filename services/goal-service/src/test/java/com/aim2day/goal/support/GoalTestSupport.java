package com.aim2day.goal.support;

import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.session.MapSession;
import org.springframework.session.MapSessionRepository;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** 목표 도메인 테스트 공통 도구: 로그인 세션·CSRF 붙이기, 테이블 비우기. */
public final class GoalTestSupport {

	public static final String TOKEN = "csrf-test-token";

	private GoalTestSupport() {
	}

	/** 메모리 세션 저장소에 userId 세션을 만들고 쿠키·CSRF 헤더를 붙인다. */
	public static MockHttpServletRequestBuilder authed(MapSessionRepository sessions,
			MockHttpServletRequestBuilder builder, long userId) {
		MapSession session = sessions.createSession();
		session.setAttribute("userId", userId);
		sessions.save(session);
		String sid = Base64.getEncoder().encodeToString(session.getId().getBytes(StandardCharsets.UTF_8));
		return builder.cookie(new Cookie("SESSION", sid), new Cookie("XSRF-TOKEN", TOKEN))
				.header("X-XSRF-TOKEN", TOKEN);
	}

	/** 모든 테스트 클래스가 같은 H2를 쓰므로 자기 테이블을 FK 순서대로 비우고 사용자 1~3을 보장한다. */
	public static void clean(JdbcTemplate jdbc) {
		jdbc.update("DELETE FROM goal_progress_logs");
		jdbc.update("DELETE FROM weekly_goal_numeric_records");
		jdbc.update("DELETE FROM action_quadrant_placements");
		jdbc.update("DELETE FROM actions");
		jdbc.update("DELETE FROM weekly_goals");
		jdbc.update("DELETE FROM monthly_goals");
		jdbc.update("DELETE FROM calendar_items");
		jdbc.update("DELETE FROM yearly_goal_criteria");
		jdbc.update("DELETE FROM yearly_goals");
		jdbc.update("DELETE FROM user_settings");
		for (long id : new long[] {1L, 2L, 3L}) {
			jdbc.update("MERGE INTO users (id) KEY (id) VALUES (?)", id);
		}
	}

	public static long idOf(MvcResult result) throws Exception {
		return ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.data.id")).longValue();
	}

	public static <T> T read(MvcResult result, String path) throws Exception {
		return JsonPath.read(result.getResponse().getContentAsString(), path);
	}

	/**
	 * 목표 트리 시드. API를 거치지 않고 JDBC로 바로 넣어 시계·한도·부모 검사와 무관하게 원하는 상태를 만든다.
	 * 진행률·현재값은 DB 기본값(0)이다.
	 */
	public static long seedYearly(JdbcTemplate jdbc, long userId, int year, String title) {
		Map<String, Object> cols = new HashMap<>();
		cols.put("user_id", userId);
		cols.put("target_year", year);
		cols.put("title", title);
		return insert(jdbc, "yearly_goals", cols);
	}

	public static long seedMonthly(JdbcTemplate jdbc, long yearlyId, LocalDate start, LocalDate end, String title) {
		Map<String, Object> cols = new HashMap<>();
		cols.put("yearly_goal_id", yearlyId);
		cols.put("start_date", Date.valueOf(start));
		cols.put("end_date", Date.valueOf(end));
		cols.put("title", title);
		return insert(jdbc, "monthly_goals", cols);
	}

	/** type이 NUMERIC이면 target을 넣는다. weight는 1.00이다. */
	public static long seedWeekly(JdbcTemplate jdbc, long monthlyId, LocalDate start, LocalDate end, String title,
			String type, BigDecimal target) {
		Map<String, Object> cols = new HashMap<>();
		cols.put("monthly_goal_id", monthlyId);
		cols.put("start_date", Date.valueOf(start));
		cols.put("end_date", Date.valueOf(end));
		cols.put("title", title);
		cols.put("measurement_type", type);
		cols.put("target_value", target);
		return insert(jdbc, "weekly_goals", cols);
	}

	/**
	 * 행동 시드. completed면 completed_at(2026-11-12T03:00Z), deleted면 deleted_at을 채운다.
	 * weeklyId가 null이면 MAINTENANCE다. 기여 기록·진행률은 건드리지 않는다.
	 */
	public static long seedAction(JdbcTemplate jdbc, long userId, Long weeklyId, String title, LocalDate performed,
			boolean completed, boolean deleted) {
		Map<String, Object> cols = new HashMap<>();
		cols.put("user_id", userId);
		cols.put("weekly_goal_id", weeklyId);
		cols.put("title", title);
		cols.put("performed_date", Date.valueOf(performed));
		cols.put("is_completed", completed);
		Timestamp stamp = Timestamp.valueOf(LocalDateTime.of(2026, 11, 12, 3, 0));
		cols.put("completed_at", completed ? stamp : null);
		cols.put("deleted_at", deleted ? stamp : null);
		return insert(jdbc, "actions", cols);
	}

	private static long insert(JdbcTemplate jdbc, String table, Map<String, Object> cols) {
		return new SimpleJdbcInsert(jdbc).withTableName(table).usingColumns(cols.keySet().toArray(new String[0])).usingGeneratedKeyColumns("id")
				.executeAndReturnKey(cols).longValue();
	}
}
