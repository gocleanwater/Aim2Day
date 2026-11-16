package com.aim2day.goal.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.aim2day.goal.support.TestSessionConfig;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

/** 실제 Tomcat에 HTTP 요청을 보내 Swagger UI와 정적 리소스가 /docs/goal/ 아래에서만 서빙되는지 확인한다. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestSessionConfig.class)
class SwaggerTests {

	@LocalServerPort
	int port;

	private final HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

	private HttpResponse<String> get(String pathAndQuery) throws Exception {
		return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + pathAndQuery)).build(),
				HttpResponse.BodyHandlers.ofString());
	}

	@Test
	void swaggerUiAndAssetsAreServedUnderDocsGoalPrefix() throws Exception {
		HttpResponse<String> entry = get("/docs/goal/swagger-ui.html");
		System.out.println("[swagger] swagger-ui.html -> " + entry.statusCode() + " location="
				+ entry.headers().firstValue("Location").orElse("-"));
		// swagger-ui.html은 실제 UI(index.html)로 리다이렉트하거나 직접 200을 준다. 어느 쪽이든 접두사 안이어야 한다.
		URI indexUri = URI.create("/docs/goal/swagger-ui.html");
		if (entry.statusCode() == 302) {
			String location = entry.headers().firstValue("Location").orElseThrow();
			indexUri = URI.create(location);
			assertThat(indexUri.getPath()).startsWith("/docs/goal/");
		} else {
			assertThat(entry.statusCode()).isEqualTo(200);
		}
		String indexPathAndQuery = indexUri.getRawPath() + (indexUri.getRawQuery() != null ? "?" + indexUri.getRawQuery() : "");
		HttpResponse<String> index = get(indexPathAndQuery);
		System.out.println("[swagger] index " + indexPathAndQuery + " -> " + index.statusCode());
		assertThat(index.statusCode()).isEqualTo(200);

		// index.html이 참조하는 css/js 경로가 전부 /docs/goal/ 아래이고 실제로 200인지 확인한다.
		Matcher m = Pattern.compile("(?:src|href)=\"([^\"]+\\.(?:js|css|png))\"").matcher(index.body());
		int checked = 0;
		while (m.find()) {
			String ref = m.group(1);
			String path = URI.create("http://localhost" + indexUri.getPath()).resolve(ref).getPath();
			System.out.println("[swagger] asset " + ref + " -> " + path);
			assertThat(path).startsWith("/docs/goal/");
			assertThat(get(path).statusCode()).as(path).isEqualTo(200);
			checked++;
		}
		assertThat(checked).isGreaterThan(0);

		HttpResponse<String> cfg = get("/docs/goal/v3/api-docs/swagger-config");
		System.out.println("[swagger] swagger-config -> " + cfg.statusCode() + " " + cfg.body());
		assertThat(cfg.statusCode()).isEqualTo(200);
		assertThat(cfg.body()).contains("/docs/goal/v3/api-docs");

		// CSRF 헤더 자동 전송 설정은 swagger-initializer.js(springdoc이 동적으로 생성)에 들어간다.
		HttpResponse<String> init = get("/docs/goal/swagger-ui/swagger-initializer.js");
		System.out.println("[swagger] initializer -> " + init.body().replace('\n', ' '));
		assertThat(init.body()).contains("XSRF-TOKEN").contains("X-XSRF-TOKEN");
	}

	@Test
	void apiDocsJsonIsServed() throws Exception {
		HttpResponse<String> docs = get("/docs/goal/v3/api-docs");
		assertThat(docs.statusCode()).isEqualTo(200);
		assertThat(docs.body()).contains("\"openapi\"");
	}
}
