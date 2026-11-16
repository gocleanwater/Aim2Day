package com.aim2day.goal.progress;

import static com.aim2day.goal.support.GoalTestSupport.authed;
import static com.aim2day.goal.support.GoalTestSupport.idOf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aim2day.goal.support.GoalTestSupport;
import com.aim2day.goal.support.MutableClock;
import com.aim2day.goal.support.TestClockConfig;
import com.aim2day.goal.support.TestSessionConfig;
import java.math.BigDecimal;
import java.sql.Timestamp;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.session.MapSessionRepository;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@Import({TestSessionConfig.class, TestClockConfig.class})
class ProgressLogApiTests {

	@Autowired
	MockMvc mockMvc;
	@Autowired
	MapSessionRepository sessionRepository;
	@Autowired
	JdbcTemplate jdbc;
	@Autowired
	MutableClock clock;

	@BeforeEach
	void reset() {
		clock.reset();
		GoalTestSupport.clean(jdbc);
	}

	private long createGoal(long userId, String title) throws Exception {
		return idOf(mockMvc.perform(authed(sessionRepository, post("/api/goals/yearly"), userId)
				.contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"" + title + "\"}")).andReturn());
	}

	/** created_at을 직접 지정해 정렬 규칙(시각 내림차순, 같은 시각이면 id 내림차순)을 결정적으로 검증한다. */
	private void insertLog(long userId, long goalId, String prev, String next, String createdAt, String memo) {
		jdbc.update("INSERT INTO goal_progress_logs (user_id, target_type, target_id, previous_rate, new_rate, "
				+ "change_type, log_date, memo, created_at) VALUES (?, 'YEARLY_GOAL', ?, ?, ?, 'PROGRESS_UPDATE', "
				+ "'2026-11-12', ?, ?)", userId, goalId, new BigDecimal(prev), new BigDecimal(next),
				memo, Timestamp.valueOf(createdAt));
	}

	@Test
	void recentFirstWithTargetTitle() throws Exception {
		long goal = createGoal(1L, "백엔드 취업");
		insertLog(1L, goal, "0.00", "10.00", "2026-11-10 01:00:00", "첫째");
		insertLog(1L, goal, "10.00", "40.00", "2026-11-12 01:00:00", "셋째-먼저 삽입");
		insertLog(1L, goal, "40.00", "60.00", "2026-11-12 01:00:00", "셋째-나중 삽입");
		insertLog(1L, goal, "60.00", "70.00", "2026-11-11 01:00:00", "둘째");
		mockMvc.perform(authed(sessionRepository, get("/api/goals/progress-logs"), 1L))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.length()").value(4))
				// 같은 시각이면 id가 큰(나중에 쓴) 기록이 먼저
				.andExpect(jsonPath("$.data[0].memo").value("셋째-나중 삽입"))
				.andExpect(jsonPath("$.data[1].memo").value("셋째-먼저 삽입"))
				.andExpect(jsonPath("$.data[2].memo").value("둘째"))
				.andExpect(jsonPath("$.data[3].memo").value("첫째"))
				.andExpect(jsonPath("$.data[0].targetType").value("YEARLY_GOAL"))
				.andExpect(jsonPath("$.data[0].targetId").value(goal))
				.andExpect(jsonPath("$.data[0].targetTitle").value("백엔드 취업"))
				.andExpect(jsonPath("$.data[0].previousRate").value(40.0))
				.andExpect(jsonPath("$.data[0].newRate").value(60.0))
				.andExpect(jsonPath("$.data[0].changeType").value("PROGRESS_UPDATE"))
				.andExpect(jsonPath("$.data[0].logDate").value("2026-11-12"))
				.andExpect(jsonPath("$.data[0].createdAt").value("2026-11-12T01:00:00Z"));
	}

	@Test
	void deletedGoalKeepsItsTitleInLogs() throws Exception {
		long goal = createGoal(1L, "지워진 목표");
		insertLog(1L, goal, "0.00", "10.00", "2026-11-10 01:00:00", "기록");
		jdbc.update("UPDATE yearly_goals SET deleted_at = CURRENT_TIMESTAMP WHERE id = ?", goal);
		mockMvc.perform(authed(sessionRepository, get("/api/goals/progress-logs"), 1L))
				.andExpect(jsonPath("$.data[0].targetTitle").value("지워진 목표"));
	}

	@Test
	void limitDefaultsTo10AndRange1To50() throws Exception {
		long goal = createGoal(1L, "목표");
		for (int i = 0; i < 12; i++) {
			insertLog(1L, goal, "0.00", "1.00", "2026-11-10 01:00:" + String.format("%02d", i), "m" + i);
		}
		mockMvc.perform(authed(sessionRepository, get("/api/goals/progress-logs"), 1L))
				.andExpect(jsonPath("$.data.length()").value(10))
				.andExpect(jsonPath("$.data[0].memo").value("m11"));
		mockMvc.perform(authed(sessionRepository, get("/api/goals/progress-logs?limit=3"), 1L))
				.andExpect(jsonPath("$.data.length()").value(3));
		mockMvc.perform(authed(sessionRepository, get("/api/goals/progress-logs?limit=50"), 1L))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.length()").value(12));
		for (String limit : new String[] {"0", "51", "-1"}) {
			mockMvc.perform(authed(sessionRepository, get("/api/goals/progress-logs?limit=" + limit), 1L))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
					.andExpect(jsonPath("$.details.fieldErrors[0].field").value("limit"))
					.andExpect(jsonPath("$.details.fieldErrors[0].reason").value("OUT_OF_RANGE"));
		}
		mockMvc.perform(authed(sessionRepository, get("/api/goals/progress-logs?limit=abc"), 1L))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
	}

	@Test
	void onlyOwnLogs() throws Exception {
		long mine = createGoal(1L, "내 목표");
		long theirs = createGoal(2L, "남의 목표");
		insertLog(1L, mine, "0.00", "10.00", "2026-11-10 01:00:00", "내 기록");
		insertLog(2L, theirs, "0.00", "20.00", "2026-11-11 01:00:00", "남의 기록");
		mockMvc.perform(authed(sessionRepository, get("/api/goals/progress-logs"), 1L))
				.andExpect(jsonPath("$.data.length()").value(1))
				.andExpect(jsonPath("$.data[0].memo").value("내 기록"));
		mockMvc.perform(authed(sessionRepository, get("/api/goals/progress-logs"), 3L))
				.andExpect(jsonPath("$.data").isEmpty());
	}
}
