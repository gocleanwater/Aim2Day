package com.aim2day.goal.goals.yearly;

import static com.aim2day.goal.support.GoalTestSupport.authed;
import static com.aim2day.goal.support.GoalTestSupport.idOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aim2day.goal.goals.GoalStatus;
import com.aim2day.goal.goals.yearly.dto.YearlyGoalDtos.UpdateYearlyGoalRequest;
import com.aim2day.goal.support.GoalTestSupport;
import com.aim2day.goal.support.MutableClock;
import com.aim2day.goal.support.TestClockConfig;
import com.aim2day.goal.support.TestSessionConfig;
import java.time.Instant;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@AutoConfigureMockMvc
@Import({TestSessionConfig.class, TestClockConfig.class})
class YearlyGoalUpdateTests {

	@Autowired
	MockMvc mockMvc;
	@Autowired
	MapSessionRepository sessionRepository;
	@Autowired
	JdbcTemplate jdbc;
	@Autowired
	MutableClock clock;
	@Autowired
	JsonMapper jsonMapper;

	@BeforeEach
	void reset() {
		clock.reset();
		GoalTestSupport.clean(jdbc);
	}

	private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder builder, long userId, String body) {
		return authed(sessionRepository, builder, userId).contentType(MediaType.APPLICATION_JSON).content(body);
	}

	private long createGoal(long userId, String body) throws Exception {
		return idOf(mockMvc.perform(json(post("/api/goals/yearly"), userId, body)).andReturn());
	}

	private MockHttpServletRequestBuilder patchGoal(long userId, long id, String body) {
		return json(patch("/api/goals/yearly/" + id), userId, body);
	}

	/**
	 * Jackson 3에서 Optional이 "필드 없음"(null)과 "JSON null"(Optional.empty)을 구분해야
	 * PATCH가 "바꾸지 않음"과 "값 지움"을 나눌 수 있다. HTTP 계층을 거치지 않고 먼저 직접 확인한다.
	 */
	@Test
	void optionalDistinguishesAbsentFromJsonNull() throws Exception {
		UpdateYearlyGoalRequest absent = jsonMapper.readValue("{\"version\":0}", UpdateYearlyGoalRequest.class);
		assertThat(absent.title()).isNull();
		assertThat(absent.description()).isNull();
		assertThat(absent.status()).isNull();

		UpdateYearlyGoalRequest explicitNull = jsonMapper.readValue(
				"{\"title\":null,\"description\":null,\"color\":null,\"status\":null,\"version\":0}",
				UpdateYearlyGoalRequest.class);
		assertThat(explicitNull.title()).isNotNull().isEmpty();
		assertThat(explicitNull.description()).isNotNull().isEmpty();
		assertThat(explicitNull.color()).isNotNull().isEmpty();
		assertThat(explicitNull.status()).isNotNull().isEmpty();

		UpdateYearlyGoalRequest values = jsonMapper.readValue(
				"{\"title\":\"t\",\"status\":\"ON_HOLD\",\"version\":3}", UpdateYearlyGoalRequest.class);
		assertThat(values.title()).contains("t");
		assertThat(values.status()).contains(GoalStatus.ON_HOLD);
		assertThat(values.version()).isEqualTo(3L);
	}

	@Test
	void detailHasEmptyMonthlyGoalsForNow() throws Exception {
		long id = createGoal(1L, "{\"title\":\"백엔드\"}");
		mockMvc.perform(authed(sessionRepository, get("/api/goals/yearly/" + id), 1L))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.goal.id").value(id))
				.andExpect(jsonPath("$.data.goal.title").value("백엔드"))
				.andExpect(jsonPath("$.data.monthlyGoals").isArray())
				.andExpect(jsonPath("$.data.monthlyGoals").isEmpty());
	}

	@Test
	void patchChangesOnlySentFields() throws Exception {
		long id = createGoal(1L, "{\"title\":\"원래\",\"description\":\"설명\",\"color\":\"#10B981\"}");
		mockMvc.perform(patchGoal(1L, id, "{\"title\":\"새 제목\",\"version\":0}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.title").value("새 제목"))
				.andExpect(jsonPath("$.data.description").value("설명"))
				.andExpect(jsonPath("$.data.color").value("#10B981"))
				.andExpect(jsonPath("$.data.status").value("PROGRESS"))
				.andExpect(jsonPath("$.data.version").value(1));
		// 저장된 값도 같다(응답만 맞고 DB는 다른 일이 없게 확인).
		assertThat(jdbc.queryForObject("SELECT title FROM yearly_goals WHERE id = ?", String.class, id))
				.isEqualTo("새 제목");
	}

	@Test
	void patchNormalizesColorAndTrimsTitle() throws Exception {
		long id = createGoal(1L, "{\"title\":\"x\"}");
		mockMvc.perform(patchGoal(1L, id, "{\"title\":\"  y  \",\"color\":\"#abcdef\",\"version\":0}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.title").value("y"))
				.andExpect(jsonPath("$.data.color").value("#ABCDEF"))
				.andExpect(jsonPath("$.data.displayColor").value("#ABCDEF"));
	}

	@Test
	void patchNullDescriptionClears() throws Exception {
		long id = createGoal(1L, "{\"title\":\"x\",\"description\":\"지워질 설명\"}");
		mockMvc.perform(patchGoal(1L, id, "{\"description\":null,\"version\":0}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.description").doesNotExist())
				.andExpect(jsonPath("$.data.title").value("x"))
				.andExpect(jsonPath("$.data.version").value(1));
		assertThat(jdbc.queryForObject("SELECT description FROM yearly_goals WHERE id = ?", String.class, id)).isNull();
	}

	@Test
	void patchAbsentDescriptionKeepsIt() throws Exception {
		long id = createGoal(1L, "{\"title\":\"x\",\"description\":\"유지\"}");
		mockMvc.perform(patchGoal(1L, id, "{\"title\":\"y\",\"version\":0}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.description").value("유지"));
	}

	@Test
	void patchNullTitleIs400() throws Exception {
		long id = createGoal(1L, "{\"title\":\"x\"}");
		for (String field : new String[] {"title", "color", "status"}) {
			mockMvc.perform(patchGoal(1L, id, "{\"" + field + "\":null,\"version\":0}"))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
					.andExpect(jsonPath("$.details.fieldErrors[0].field").value(field))
					.andExpect(jsonPath("$.details.fieldErrors[0].reason").value("REQUIRED"));
		}
	}

	@Test
	void patchInvalidValuesAre400() throws Exception {
		long id = createGoal(1L, "{\"title\":\"x\"}");
		mockMvc.perform(patchGoal(1L, id, "{\"title\":\"" + "가".repeat(101) + "\",\"version\":0}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.details.fieldErrors[0].reason").value("TOO_LONG"));
		mockMvc.perform(patchGoal(1L, id, "{\"color\":\"red\",\"version\":0}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.details.fieldErrors[0].field").value("color"))
				.andExpect(jsonPath("$.details.fieldErrors[0].reason").value("INVALID_FORMAT"));
		mockMvc.perform(patchGoal(1L, id, "{\"status\":\"FINISHED\",\"version\":0}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
	}

	@Test
	void patchWithoutVersionIs400() throws Exception {
		long id = createGoal(1L, "{\"title\":\"x\"}");
		mockMvc.perform(patchGoal(1L, id, "{\"title\":\"y\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.details.fieldErrors[0].field").value("version"));
	}

	@Test
	void patchStaleVersionIs409WithCurrentVersion() throws Exception {
		long id = createGoal(1L, "{\"title\":\"x\"}");
		mockMvc.perform(patchGoal(1L, id, "{\"title\":\"y\",\"version\":0}")).andExpect(status().isOk());
		mockMvc.perform(patchGoal(1L, id, "{\"title\":\"z\",\"version\":0}"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("VERSION_CONFLICT"))
				.andExpect(jsonPath("$.details.currentVersion").value(1));
		assertThat(jdbc.queryForObject("SELECT title FROM yearly_goals WHERE id = ?", String.class, id)).isEqualTo("y");
	}

	@Test
	void patchIgnoresTargetYear() throws Exception {
		long id = createGoal(1L, "{\"title\":\"x\"}");
		mockMvc.perform(patchGoal(1L, id, "{\"targetYear\":2027,\"title\":\"y\",\"version\":0}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.targetYear").value(2026));
	}

	@Test
	void onHoldSetsPathStatus() throws Exception {
		long id = createGoal(1L, "{\"title\":\"x\"}");
		mockMvc.perform(patchGoal(1L, id, "{\"status\":\"ON_HOLD\",\"version\":0}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.status").value("ON_HOLD"))
				.andExpect(jsonPath("$.data.pathStatus").value("ON_HOLD"));
		mockMvc.perform(patchGoal(1L, id, "{\"status\":\"DROPPED\",\"version\":1}"))
				.andExpect(jsonPath("$.data.pathStatus").value("DROPPED"));
		mockMvc.perform(patchGoal(1L, id, "{\"status\":\"PROGRESS\",\"version\":2}"))
				.andExpect(jsonPath("$.data.pathStatus").doesNotExist());
	}

	@Test
	void pastYearNotCompletedIsDelayed() throws Exception {
		long id = createGoal(1L, "{\"title\":\"x\"}");
		// 2026-12-31 당일은 아직 지연이 아니다(종료일이 오늘보다 앞서야 한다).
		clock.set(Instant.parse("2026-12-31T03:00:00Z"));
		mockMvc.perform(authed(sessionRepository, get("/api/goals/yearly/" + id), 1L))
				.andExpect(jsonPath("$.data.goal.isDelayed").value(false));
		clock.set(Instant.parse("2027-01-02T03:00:00Z"));
		mockMvc.perform(authed(sessionRepository, get("/api/goals/yearly/" + id), 1L))
				.andExpect(jsonPath("$.data.goal.isDelayed").value(true));
		mockMvc.perform(patchGoal(1L, id, "{\"status\":\"COMPLETED\",\"version\":0}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.isDelayed").value(false));
	}

	@Test
	void otherUsersGoalIs404() throws Exception {
		long id = createGoal(1L, "{\"title\":\"x\"}");
		mockMvc.perform(authed(sessionRepository, get("/api/goals/yearly/" + id), 2L))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
				.andExpect(jsonPath("$.details.resource").value("YEARLY_GOAL"))
				.andExpect(jsonPath("$.details.id").value(id));
		mockMvc.perform(patchGoal(2L, id, "{\"title\":\"탈취\",\"version\":0}"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.details.resource").value("YEARLY_GOAL"));
		assertThat(jdbc.queryForObject("SELECT title FROM yearly_goals WHERE id = ?", String.class, id)).isEqualTo("x");
	}

	@Test
	void softDeletedGoalIs404() throws Exception {
		long id = createGoal(1L, "{\"title\":\"x\"}");
		jdbc.update("UPDATE yearly_goals SET deleted_at = CURRENT_TIMESTAMP WHERE id = ?", id);
		mockMvc.perform(authed(sessionRepository, get("/api/goals/yearly/" + id), 1L))
				.andExpect(status().isNotFound());
		mockMvc.perform(authed(sessionRepository, get("/api/goals/yearly?year=2026"), 1L))
				.andExpect(jsonPath("$.data.activeCount").value(0));
	}
}
