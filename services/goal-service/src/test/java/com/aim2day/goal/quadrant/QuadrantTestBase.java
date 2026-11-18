package com.aim2day.goal.quadrant;

import com.aim2day.goal.support.ApiTestBase;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 사분면 테스트 공통 준비. 시계는 2026-11-12T03:00Z(서울 목요일 12:00)라 오늘은 2026-11-12이고 이번 주는 11-08~11-14다.
 * 사용자 1에게 연간 -> 월간 -> 체크형 주간(w1)을 만들어 둔다.
 */
abstract class QuadrantTestBase extends ApiTestBase {

	long yearly;
	long monthly;
	long w1;

	@BeforeEach
	void seedGoals() throws Exception {
		yearly = api.yearly(1L, "연간");
		monthly = api.monthly(1L, yearly, "월간");
		w1 = api.weekly(1L, monthly, "체크 주간");
	}

	MvcResult put(long userId, long actionId, String body) throws Exception {
		return api.put("/api/actions/" + actionId + "/placement", userId, body);
	}

	MvcResult put(long actionId, int importance, int urgency) throws Exception {
		return put(1L, actionId, "{\"importance\":" + importance + ",\"urgency\":" + urgency + "}");
	}

	MvcResult putTray(long actionId) throws Exception {
		return put(1L, actionId, "{\"importance\":null,\"urgency\":null}");
	}

	MvcResult cancel(long actionId) throws Exception {
		return api.delete("/api/actions/" + actionId + "/placement/pending", 1L);
	}

	MvcResult confirm() throws Exception {
		return api.post("/api/actions/placements/confirm", 1L, null);
	}

	void tomorrow() {
		clock.set(Instant.parse("2026-11-13T03:00:00Z"));
	}

	int rows(long actionId) {
		return jdbc.queryForObject("SELECT COUNT(*) FROM action_quadrant_placements WHERE action_id = ?",
				Integer.class, actionId);
	}

	boolean pendingUnclassify(long actionId) {
		return jdbc.queryForObject("SELECT is_pending_unclassify FROM action_quadrant_placements WHERE action_id = ?",
				Boolean.class, actionId);
	}
}
