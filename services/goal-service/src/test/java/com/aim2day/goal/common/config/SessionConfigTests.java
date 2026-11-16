package com.aim2day.goal.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.aim2day.goal.support.TestSessionConfig;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.session.data.redis.RedisIndexedSessionRepository;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.CookieSerializer.CookieValue;
import org.springframework.test.util.ReflectionTestUtils;

/** 어노테이션 값이 실제 저장소에 적용되었는지, 쿠키 속성이 명세와 같은지 확인한다(user-service와 같아야 하는 값). */
@SpringBootTest
@Import(TestSessionConfig.class)
class SessionConfigTests {

	@Autowired
	RedisIndexedSessionRepository redisRepository;
	@Autowired
	CookieSerializer cookieSerializer;

	@Test
	void indexedRepositoryUsesNamespaceAndThirtyMinuteTimeout() {
		assertThat(ReflectionTestUtils.getField(redisRepository, "namespace")).isEqualTo("spring:session:");
		assertThat(ReflectionTestUtils.getField(redisRepository, "defaultMaxInactiveInterval"))
				.isEqualTo(Duration.ofMinutes(30));
	}

	@Test
	void sessionCookieHasSpecifiedAttributes() {
		MockHttpServletRequest request = new MockHttpServletRequest();
		MockHttpServletResponse response = new MockHttpServletResponse();
		cookieSerializer.writeCookieValue(new CookieValue(request, response, "abc"));

		String setCookie = response.getHeader("Set-Cookie");
		System.out.println("[session] Set-Cookie: " + setCookie);
		assertThat(setCookie).startsWith("SESSION=").contains("HttpOnly").contains("SameSite=Lax").contains("Path=/");
	}
}
