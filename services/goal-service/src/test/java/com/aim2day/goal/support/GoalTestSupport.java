package com.aim2day.goal.support;

import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.springframework.jdbc.core.JdbcTemplate;
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
}
