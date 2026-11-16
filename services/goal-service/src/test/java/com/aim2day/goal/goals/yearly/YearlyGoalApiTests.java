package com.aim2day.goal.goals.yearly;

import static com.aim2day.goal.support.GoalTestSupport.authed;
import static com.aim2day.goal.support.GoalTestSupport.idOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aim2day.goal.support.GoalTestSupport;
import com.aim2day.goal.support.MutableClock;
import com.aim2day.goal.support.TestClockConfig;
import com.aim2day.goal.support.TestSessionConfig;
import java.util.List;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest
@AutoConfigureMockMvc
@Import({TestSessionConfig.class, TestClockConfig.class})
class YearlyGoalApiTests {

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

	private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder builder, long userId, String body) {
		return authed(sessionRepository, builder, userId).contentType(MediaType.APPLICATION_JSON).content(body);
	}

	private MvcResult create(long userId, String body) throws Exception {
		return mockMvc.perform(json(post("/api/goals/yearly"), userId, body)).andReturn();
	}

	private long createOk(long userId, String title) throws Exception {
		MvcResult result = create(userId, "{\"title\":\"" + title + "\"}");
		assertThat(result.getResponse().getStatus()).isEqualTo(201);
		return idOf(result);
	}

	@Test
	void createDefaultsToCurrentYearAndBlueColor() throws Exception {
		MvcResult result = mockMvc.perform(json(post("/api/goals/yearly"), 1L, "{\"title\":\"백엔드\"}"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.code").value("SUCCESS"))
				.andExpect(jsonPath("$.data.targetYear").value(2026))
				.andExpect(jsonPath("$.data.title").value("백엔드"))
				.andExpect(jsonPath("$.data.color").value("#3B82F6"))
				.andExpect(jsonPath("$.data.displayColor").value("#3B82F6"))
				.andExpect(jsonPath("$.data.displayOrder").value(0))
				.andExpect(jsonPath("$.data.status").value("PROGRESS"))
				.andExpect(jsonPath("$.data.pathStatus").doesNotExist())
				.andExpect(jsonPath("$.data.isDelayed").value(false))
				.andExpect(jsonPath("$.data.criteria.state").value("NOT_CONFIGURED"))
				.andExpect(jsonPath("$.data.criteria.achievementRate").doesNotExist())
				.andExpect(jsonPath("$.data.version").value(0))
				.andReturn();
		// 일시는 UTC(Z)로 나가야 한다. DB 기본값이 채운 값을 INSERT 직후 다시 읽어 응답에 담는다.
		String createdAt = GoalTestSupport.read(result, "$.data.createdAt");
		assertThat(createdAt).endsWith("Z");
		assertThat((String) GoalTestSupport.read(result, "$.data.updatedAt")).endsWith("Z");
	}

	@Test
	void titleIsTrimmed() throws Exception {
		mockMvc.perform(json(post("/api/goals/yearly"), 1L, "{\"title\":\"  앞뒤 공백  \"}"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.data.title").value("앞뒤 공백"));
	}

	@Test
	void displayOrderAppendsToEnd() throws Exception {
		createOk(1L, "a");
		mockMvc.perform(json(post("/api/goals/yearly"), 1L, "{\"title\":\"b\"}"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.data.displayOrder").value(1));
	}

	@Test
	void fourthActiveGoalIs409() throws Exception {
		createOk(1L, "a");
		createOk(1L, "b");
		createOk(1L, "c");
		mockMvc.perform(json(post("/api/goals/yearly"), 1L, "{\"title\":\"d\"}"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("GOAL_LIMIT_EXCEEDED"))
				.andExpect(jsonPath("$.details.year").value(2026))
				.andExpect(jsonPath("$.details.current").value(3))
				.andExpect(jsonPath("$.details.max").value(3));
	}

	@Test
	void limitIsPerYear() throws Exception {
		createOk(1L, "a");
		createOk(1L, "b");
		createOk(1L, "c");
		mockMvc.perform(json(post("/api/goals/yearly"), 1L, "{\"targetYear\":2027,\"title\":\"새해\"}"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.data.targetYear").value(2027));
	}

	@Test
	void limitIsPerUser() throws Exception {
		createOk(1L, "a");
		createOk(1L, "b");
		createOk(1L, "c");
		mockMvc.perform(json(post("/api/goals/yearly"), 2L, "{\"title\":\"다른 사용자\"}"))
				.andExpect(status().isCreated());
	}

	@Test
	void targetYearOutsideThisAndNextIs400() throws Exception {
		for (int year : new int[] {2025, 2028}) {
			mockMvc.perform(json(post("/api/goals/yearly"), 1L,
							"{\"targetYear\":" + year + ",\"title\":\"x\"}"))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
					.andExpect(jsonPath("$.details.fieldErrors[0].field").value("targetYear"))
					.andExpect(jsonPath("$.details.fieldErrors[0].reason").value("OUT_OF_RANGE"));
		}
	}

	@Test
	void titleBlankOrTooLongIs400() throws Exception {
		mockMvc.perform(json(post("/api/goals/yearly"), 1L, "{\"title\":\"   \"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.details.fieldErrors[0].field").value("title"))
				.andExpect(jsonPath("$.details.fieldErrors[0].reason").value("REQUIRED"));
		mockMvc.perform(json(post("/api/goals/yearly"), 1L, "{}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.details.fieldErrors[0].reason").value("REQUIRED"));
		mockMvc.perform(json(post("/api/goals/yearly"), 1L, "{\"title\":\"" + "가".repeat(101) + "\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.details.fieldErrors[0].field").value("title"))
				.andExpect(jsonPath("$.details.fieldErrors[0].reason").value("TOO_LONG"));
		// 100자는 통과한다.
		mockMvc.perform(json(post("/api/goals/yearly"), 1L, "{\"title\":\"" + "가".repeat(100) + "\"}"))
				.andExpect(status().isCreated());
	}

	@Test
	void descriptionOver2000Is400() throws Exception {
		mockMvc.perform(json(post("/api/goals/yearly"), 1L,
						"{\"title\":\"x\",\"description\":\"" + "a".repeat(2001) + "\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.details.fieldErrors[0].field").value("description"))
				.andExpect(jsonPath("$.details.fieldErrors[0].reason").value("TOO_LONG"));
	}

	@Test
	void badColorIs400() throws Exception {
		for (String color : new String[] {"blue", "#12345", "#GGGGGG"}) {
			mockMvc.perform(json(post("/api/goals/yearly"), 1L,
							"{\"title\":\"x\",\"color\":\"" + color + "\"}"))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.details.fieldErrors[0].field").value("color"))
					.andExpect(jsonPath("$.details.fieldErrors[0].reason").value("INVALID_FORMAT"));
		}
	}

	@Test
	void colorStoredUppercase() throws Exception {
		mockMvc.perform(json(post("/api/goals/yearly"), 1L, "{\"title\":\"x\",\"color\":\"#3b82f6\"}"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.data.color").value("#3B82F6"));
	}

	@Test
	void listSortedByDisplayOrderThenId() throws Exception {
		long a = createOk(1L, "a");
		long b = createOk(1L, "b");
		long c = createOk(1L, "c");
		mockMvc.perform(authed(sessionRepository, get("/api/goals/yearly"), 1L))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.year").value(2026))
				.andExpect(jsonPath("$.data.activeCount").value(3))
				.andExpect(jsonPath("$.data.maxCount").value(3))
				.andExpect(jsonPath("$.data.items[0].id").value(a))
				.andExpect(jsonPath("$.data.items[1].id").value(b))
				.andExpect(jsonPath("$.data.items[2].id").value(c));
		// display_order가 같으면 id 오름차순이다.
		jdbc.update("UPDATE yearly_goals SET display_order = 0");
		mockMvc.perform(authed(sessionRepository, get("/api/goals/yearly?year=2026"), 1L))
				.andExpect(jsonPath("$.data.items[0].id").value(a))
				.andExpect(jsonPath("$.data.items[2].id").value(c));
	}

	@Test
	void listOfOtherYearIsEmptyAndDefaultsToCurrentYear() throws Exception {
		createOk(1L, "a");
		mockMvc.perform(authed(sessionRepository, get("/api/goals/yearly?year=2027"), 1L))
				.andExpect(jsonPath("$.data.year").value(2027))
				.andExpect(jsonPath("$.data.activeCount").value(0))
				.andExpect(jsonPath("$.data.items").isEmpty());
	}

	@Test
	void reorderRenumbersFromZeroWithoutVersionBump() throws Exception {
		long a = createOk(1L, "a");
		long b = createOk(1L, "b");
		long c = createOk(1L, "c");
		mockMvc.perform(json(put("/api/goals/yearly/order"), 1L,
						"{\"year\":2026,\"goalIds\":[" + c + "," + a + "," + b + "]}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.items[0].id").value(c))
				.andExpect(jsonPath("$.data.items[1].id").value(a))
				.andExpect(jsonPath("$.data.items[2].id").value(b));
		mockMvc.perform(authed(sessionRepository, get("/api/goals/yearly"), 1L))
				.andExpect(jsonPath("$.data.items[0].id").value(c))
				.andExpect(jsonPath("$.data.items[0].displayOrder").value(0))
				.andExpect(jsonPath("$.data.items[1].displayOrder").value(1))
				.andExpect(jsonPath("$.data.items[2].displayOrder").value(2))
				.andExpect(jsonPath("$.data.items[0].version").value(0))
				.andExpect(jsonPath("$.data.items[1].version").value(0))
				.andExpect(jsonPath("$.data.items[2].version").value(0));
	}

	@Test
	void reorderWithMissingOrExtraIdIs422() throws Exception {
		long a = createOk(1L, "a");
		long b = createOk(1L, "b");
		// 빠진 id
		mockMvc.perform(json(put("/api/goals/yearly/order"), 1L, "{\"year\":2026,\"goalIds\":[" + a + "]}"))
				.andExpect(status().isUnprocessableContent())
				.andExpect(jsonPath("$.code").value("GOAL_ORDER_MISMATCH"))
				.andExpect(jsonPath("$.details.expectedIds[0]").value(a))
				.andExpect(jsonPath("$.details.expectedIds[1]").value(b));
		// 없는 id가 끼어 있음, 중복 id
		mockMvc.perform(json(put("/api/goals/yearly/order"), 1L,
						"{\"year\":2026,\"goalIds\":[" + a + "," + b + ",999999]}"))
				.andExpect(status().isUnprocessableContent());
		mockMvc.perform(json(put("/api/goals/yearly/order"), 1L,
						"{\"year\":2026,\"goalIds\":[" + a + "," + a + "]}"))
				.andExpect(status().isUnprocessableContent());
		// 실패한 요청은 순서를 바꾸지 않는다.
		assertThat(jdbc.queryForList("SELECT display_order FROM yearly_goals ORDER BY id", Integer.class))
				.isEqualTo(List.of(0, 1));
	}

	@Test
	void otherUsersGoalNotInListOrReorder() throws Exception {
		long mine = createOk(1L, "mine");
		long theirs = createOk(2L, "theirs");
		mockMvc.perform(authed(sessionRepository, get("/api/goals/yearly"), 1L))
				.andExpect(jsonPath("$.data.activeCount").value(1))
				.andExpect(jsonPath("$.data.items[0].id").value(mine));
		// 남의 목표 id를 내 순서 목록에 넣으면 422이고, 남의 순서는 바뀌지 않는다.
		mockMvc.perform(json(put("/api/goals/yearly/order"), 1L,
						"{\"year\":2026,\"goalIds\":[" + mine + "," + theirs + "]}"))
				.andExpect(status().isUnprocessableContent())
				.andExpect(jsonPath("$.code").value("GOAL_ORDER_MISMATCH"))
				.andExpect(jsonPath("$.details.expectedIds[0]").value(mine))
				.andExpect(jsonPath("$.details.expectedIds[1]").doesNotExist());
	}

	@Test
	void reorderRequiresYearAndGoalIds() throws Exception {
		mockMvc.perform(json(put("/api/goals/yearly/order"), 1L, "{}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
	}

	@Test
	void unauthenticatedIs401() throws Exception {
		mockMvc.perform(get("/api/goals/yearly"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
	}
}
