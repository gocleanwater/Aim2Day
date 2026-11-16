package com.aim2day.user.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aim2day.user.support.TestSessionConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** 프록시가 /docs/user/ 만 넘기므로 UI가 쓰는 모든 경로가 이 접두사 아래여야 한다. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestSessionConfig.class)
class SwaggerPathTests {

	@Autowired
	MockMvc mockMvc;

	@Test
	void swaggerUiEntryRedirectsUnderPrefix() throws Exception {
		mockMvc.perform(get("/docs/user/swagger-ui.html"))
				.andExpect(status().isFound())
				.andExpect(header().string("Location", "/docs/user/swagger-ui/index.html"));
	}

	@Test
	void swaggerUiStaticResourcesAreServedUnderPrefix() throws Exception {
		// index.html이 참조하는 정적 리소스가 모두 /docs/user/ 아래에서 200이어야 프록시를 통과한다.
		String html = mockMvc.perform(get("/docs/user/swagger-ui/index.html"))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		for (String path : new String[] { "swagger-ui.css", "swagger-ui-bundle.js", "swagger-ui-standalone-preset.js",
				"swagger-initializer.js" }) {
			mockMvc.perform(get("/docs/user/swagger-ui/" + path)).andExpect(status().isOk());
		}
		assertThat(html).doesNotContain("src=\"/swagger-ui/").doesNotContain("href=\"/swagger-ui/");
	}

	@Test
	void swaggerInitializerUsesPrefixedConfigAndCsrf() throws Exception {
		String js = mockMvc.perform(get("/docs/user/swagger-ui/swagger-initializer.js"))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		assertThat(js).contains("/docs/user/v3/api-docs/swagger-config");
		assertThat(js).contains("XSRF-TOKEN").contains("X-XSRF-TOKEN");
	}

	@Test
	void openApiJsonIsServedWithoutAuthentication() throws Exception {
		mockMvc.perform(get("/docs/user/v3/api-docs"))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("/api/auth/csrf")));
	}

	@Test
	void swaggerConfigPointsToPrefixedUrl() throws Exception {
		MvcResult result = mockMvc.perform(get("/docs/user/v3/api-docs/swagger-config"))
				.andExpect(status().isOk()).andReturn();
		String body = result.getResponse().getContentAsString();
		assertThat(body).contains("/docs/user/v3/api-docs");
	}
}
