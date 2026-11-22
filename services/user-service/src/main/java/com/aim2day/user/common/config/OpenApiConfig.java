package com.aim2day.user.common.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * springdoc(Swagger UI)이 보여 주는 API 문서의 제목·설명·버전 메타데이터를 정하는 설정 클래스.
 * 문서 화면은 /docs/user/** 경로로 노출되며(SecurityConfig에서 인증 없이 허용), 엔드포인트 목록 자체는
 * springdoc이 컨트롤러를 읽어 자동으로 만든다. 여기서는 문서 머리말만 정의한다.
 */
@Configuration
public class OpenApiConfig {

	/**
	 * OpenAPI 문서의 머리말 정보를 담은 빈. 상태를 바꾸는 요청(POST 등)은 CSRF 보호 대상이므로
	 * 문서를 읽는 사람이 헤더를 빠뜨리지 않도록 설명에 X-XSRF-TOKEN 헤더가 필요하다는 점을 적어 둔다.
	 */
	@Bean
	OpenAPI userServiceOpenApi() {
		return new OpenAPI().info(new Info()
				.title("Aim2Day user-service API")
				.description("인증·계정 API. 상태 변경 요청에는 X-XSRF-TOKEN 헤더가 필요하다.")
				.version("v1"));
	}
}
