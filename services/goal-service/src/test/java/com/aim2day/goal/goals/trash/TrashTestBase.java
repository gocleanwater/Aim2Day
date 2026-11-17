package com.aim2day.goal.goals.trash;

import com.aim2day.goal.support.GoalApiClient;
import com.aim2day.goal.support.GoalTestSupport;
import com.aim2day.goal.support.MutableClock;
import com.aim2day.goal.support.TestClockConfig;
import com.aim2day.goal.support.TestSessionConfig;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
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
 * 삭제·휴지통·복원·정리 테스트 공통 준비. 시계는 2026-11-12T03:00Z(서울 목요일 12:00), 이번 주는 11-08~11-14다.
 * 사용자 1에게 연간 -> 월간(2026-11) -> 체크형 주간(w1)·수치형 주간(n1, 목표 10)을 API로 만들어 둔다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestSessionConfig.class, TestClockConfig.class})
abstract class TrashTestBase {

	@Autowired
	MockMvc mockMvc;
	@Autowired
	MapSessionRepository sessionRepository;
	@Autowired
	JdbcTemplate jdbc;
	@Autowired
	MutableClock clock;

	GoalApiClient api;
	long yearly;
	long monthly;
	long w1;
	long n1;

	@BeforeEach
	void seedGoals() throws Exception {
		clock.reset();
		GoalTestSupport.clean(jdbc);
		api = new GoalApiClient(mockMvc, sessionRepository);
		yearly = api.yearly(1L, "연간");
		monthly = api.monthly(1L, yearly, "월간");
		w1 = api.weekly(1L, monthly, "체크 주간");
		n1 = api.numericWeekly(1L, monthly, "수치 주간", "10");
	}

	MvcResult delete(long userId, String level, long id, boolean confirm) throws Exception {
		return api.delete("/api/goals/" + level + "/" + id + (confirm ? "?confirm=true" : ""), userId);
	}

	MvcResult restore(long userId, String level, long id) throws Exception {
		return api.post("/api/goals/" + level + "/" + id + "/restore", userId, null);
	}

	/** 시계를 UTC 시각으로 옮긴다. */
	void clockAt(String instant) {
		clock.set(Instant.parse(instant));
	}

	/** 삭제 시각을 직접 심는다(기한 지난 상태·같은 시각 정렬을 만들기 위해). */
	void setDeletedAt(String table, long id, String utcDateTime) {
		jdbc.update("UPDATE " + table + " SET deleted_at = ? WHERE id = ?",
				Timestamp.valueOf(LocalDateTime.ofInstant(Instant.parse(utcDateTime), ZoneOffset.UTC)), id);
	}

	boolean isDeleted(String table, long id) {
		return count("SELECT COUNT(*) FROM " + table + " WHERE id = ? AND deleted_at IS NOT NULL", id) == 1;
	}

	boolean exists(String table, long id) {
		return count("SELECT COUNT(*) FROM " + table + " WHERE id = ?", id) == 1;
	}

	int count(String sql, Object... args) {
		return jdbc.queryForObject(sql, Integer.class, args);
	}

	BigDecimal monthlyRate(long monthlyId) {
		return jdbc.queryForObject("SELECT progress_rate FROM monthly_goals WHERE id = ?", BigDecimal.class,
				monthlyId);
	}
}
