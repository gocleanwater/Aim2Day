package com.aim2day.goal.support;

import static com.aim2day.goal.support.GoalTestSupport.authed;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import org.springframework.http.MediaType;
import org.springframework.session.MapSessionRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/** 목표 API 테스트용 호출 도우미. 세션·CSRF를 붙여 요청하고, 목표 트리를 API로 만드는 단축 메서드를 둔다. */
public class GoalApiClient {

	private final MockMvc mockMvc;
	private final MapSessionRepository sessions;

	public GoalApiClient(MockMvc mockMvc, MapSessionRepository sessions) {
		this.mockMvc = mockMvc;
		this.sessions = sessions;
	}

	private MvcResult run(MockHttpServletRequestBuilder builder, long userId, String body) throws Exception {
		MockHttpServletRequestBuilder authed = authed(sessions, builder, userId);
		if (body != null) {
			authed.contentType(MediaType.APPLICATION_JSON).content(body);
		}
		return mockMvc.perform(authed).andReturn();
	}

	public MvcResult get(String path, long userId) throws Exception {
		return run(MockMvcRequestBuilders.get(path), userId, null);
	}

	public MvcResult post(String path, long userId, String body) throws Exception {
		return run(MockMvcRequestBuilders.post(path), userId, body);
	}

	public MvcResult put(String path, long userId, String body) throws Exception {
		return run(MockMvcRequestBuilders.put(path), userId, body);
	}

	public MvcResult patch(String path, long userId, String body) throws Exception {
		return run(MockMvcRequestBuilders.patch(path), userId, body);
	}

	public MvcResult delete(String path, long userId) throws Exception {
		return run(MockMvcRequestBuilders.delete(path), userId, null);
	}

	public int status(MvcResult result) {
		return result.getResponse().getStatus();
	}

	/** 상태 코드와 (JSON 경로, 기대값) 쌍을 확인한다. 기대값이 null이면 그 경로가 없거나 null이어야 한다. */
	public void expect(MvcResult result, int expectedStatus, Object... pathAndValue) throws Exception {
		assertThat(status(result)).as(body(result)).isEqualTo(expectedStatus);
		for (int i = 0; i < pathAndValue.length; i += 2) {
			String path = (String) pathAndValue[i];
			Object value = pathAndValue[i + 1];
			if (value == null) {
				jsonPath(path).doesNotExist().match(result);
			} else {
				jsonPath(path).value(value).match(result);
			}
		}
	}

	public String body(MvcResult result) throws Exception {
		return result.getResponse().getContentAsString();
	}

	/** 연간 목표를 만든다(201 확인). extra는 {@code ,"targetYear":2027} 같은 추가 JSON 조각이다. */
	public long yearly(long userId, String title, String extra) throws Exception {
		MvcResult result = post("/api/goals/yearly", userId, "{\"title\":\"" + title + "\"" + extra + "}");
		assertThat(status(result)).as(body(result)).isEqualTo(201);
		return GoalTestSupport.idOf(result);
	}

	public long yearly(long userId, String title) throws Exception {
		return yearly(userId, title, "");
	}

	public long monthly(long userId, long yearlyId, String title) throws Exception {
		MvcResult result = post("/api/goals/monthly", userId,
				"{\"yearlyGoalId\":" + yearlyId + ",\"title\":\"" + title + "\"}");
		assertThat(status(result)).as(body(result)).isEqualTo(201);
		return GoalTestSupport.idOf(result);
	}

	/** 체크형 주간 목표(가중치 1)를 이번 주로 만든다. */
	public long weekly(long userId, long monthlyId, String title) throws Exception {
		return weekly(userId, monthlyId, title, "");
	}

	/** extra는 {@code ,"measurementType":"NUMERIC","targetValue":10} 같은 추가 JSON 조각이다. */
	public long weekly(long userId, long monthlyId, String title, String extra) throws Exception {
		MvcResult result = post("/api/goals/weekly", userId,
				"{\"monthlyGoalId\":" + monthlyId + ",\"title\":\"" + title + "\"" + extra + "}");
		assertThat(status(result)).as(body(result)).isEqualTo(201);
		return GoalTestSupport.idOf(result);
	}

	/** 행동을 만든다(201 확인). json은 요청 본문 전체다. */
	public long action(long userId, String json) throws Exception {
		MvcResult result = post("/api/actions", userId, json);
		assertThat(status(result)).as(body(result)).isEqualTo(201);
		return GoalTestSupport.idOf(result);
	}

	/** 주간 목표에 직접 연결한 GOAL 행동. extra는 {@code ,"contributionValue":2} 같은 추가 JSON 조각이다. */
	public long goalAction(long userId, long weeklyId, String title, String extra) throws Exception {
		return action(userId, "{\"type\":\"GOAL\",\"weeklyGoalId\":" + weeklyId + ",\"title\":\"" + title + "\"" + extra + "}");
	}

	public long maintenance(long userId, String title, String extra) throws Exception {
		return action(userId, "{\"type\":\"MAINTENANCE\",\"title\":\"" + title + "\"" + extra + "}");
	}

	public MvcResult complete(long userId, long actionId, boolean completed) throws Exception {
		return put("/api/actions/" + actionId + "/completion", userId, "{\"completed\":" + completed + "}");
	}

	public long numericWeekly(long userId, long monthlyId, String title, String target) throws Exception {
		return weekly(userId, monthlyId, title, ",\"measurementType\":\"NUMERIC\",\"targetValue\":" + target);
	}
}
