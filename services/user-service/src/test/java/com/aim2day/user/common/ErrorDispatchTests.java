package com.aim2day.user.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.aim2day.user.support.TestSessionConfig;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

/** 내장 Tomcat을 실제로 띄워 필터 단계 예외가 서블릿 /error 디스패치를 거쳐 공통 JSON이 되는지 확인한다. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestSessionConfig.class)
class ErrorDispatchTests {

	@LocalServerPort
	int port;

	@Test
	void filterExceptionBecomesCommonErrorJsonWithoutLeakingMessage() throws Exception {
		HttpResponse<String> res = HttpClient.newHttpClient().send(
				HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/auth/filter-boom")).GET().build(),
				HttpResponse.BodyHandlers.ofString());
		assertThat(res.statusCode()).isEqualTo(500);
		assertThat(res.body()).contains("\"code\":\"INTERNAL_ERROR\"").contains("\"message\":")
				.contains("\"details\":null").doesNotContain("secret-filter-detail").doesNotContain("timestamp");
	}
}
