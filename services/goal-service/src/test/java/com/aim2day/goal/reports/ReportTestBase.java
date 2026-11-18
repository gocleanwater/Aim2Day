package com.aim2day.goal.reports;

import static org.assertj.core.api.Assertions.assertThat;

import com.aim2day.goal.support.ApiTestBase;
import com.aim2day.goal.support.GoalTestSupport;
import com.jayway.jsonpath.JsonPath;
import java.sql.Date;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 리포트·회고 API 테스트 공통 준비. 시계는 2026-11-12T03:00Z이고 계정 시간대는 서울이라 오늘은 2026-11-12다.
 * 목표 트리와 진행 기록은 상태를 정확히 만들려고 JDBC로 직접 넣는다.
 */
abstract class ReportTestBase extends ApiTestBase {

	@BeforeEach
	void prepare() throws Exception {
		api.expect(api.put("/api/settings", 1L, "{\"weekStartDay\":\"MONDAY\",\"timezone\":\"Asia/Seoul\",\"version\":0}"),
				200);
		// 사용자 2는 설정을 만들지 않아도 된다(첫 요청에서 기본값이 생기고 기본 시간대도 서울이다).
	}

	void setNow(String utcInstant) {
		clock.set(Instant.parse(utcInstant));
	}

	/** 진행 기록 한 건. created_at은 DB 기본값이라 같은 날짜의 순서는 id가 정한다(넣은 순서). */
	void log(long userId, long goalId, int previous, int next, String changeType, String date) {
		jdbc.update("INSERT INTO goal_progress_logs (user_id, target_type, target_id, previous_rate, new_rate, "
				+ "change_type, log_date) VALUES (?, 'YEARLY_GOAL', ?, ?, ?, ?, ?)", userId, goalId, previous, next,
				changeType, Date.valueOf(date));
	}

	long yearly(long userId, int year, String title, int order) {
		long id = GoalTestSupport.seedYearly(jdbc, userId, year, title);
		jdbc.update("UPDATE yearly_goals SET display_order = ? WHERE id = ?", order, id);
		return id;
	}

	MvcResult report(long userId, String type, String period) throws Exception {
		return api.get("/api/reports?periodType=" + type + "&period=" + period, userId);
	}

	/** 응답의 goalProgress에서 yearlyGoalId가 id인 항목의 필드 값. */
	<T> T progress(MvcResult result, long id, String field) throws Exception {
		List<T> found = JsonPath.read(api.body(result), "$.data.goalProgress[?(@.yearlyGoalId == " + id + ")]." + field);
		assertThat(found).as(api.body(result)).hasSize(1);
		return found.get(0);
	}
}
