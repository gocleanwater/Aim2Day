package com.aim2day.user.common.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

	@Bean
	OpenAPI userServiceOpenApi() {
		return new OpenAPI().info(new Info()
				.title("Aim2Day user-service API")
				.description("인증·계정 API. 상태 변경 요청에는 X-XSRF-TOKEN 헤더가 필요하다.")
				.version("v1"));
	}
}
