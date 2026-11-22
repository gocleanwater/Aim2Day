package com.aim2day.user.auth;

import com.aim2day.user.common.response.ApiResponse;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * CSRF 토큰 쿠키 발급 전용 컨트롤러. 프론트엔드는 상태 변경 요청(POST 등)을 보내기 전에 한 번 호출해
 * XSRF-TOKEN 쿠키를 받아 두고, 그 값을 X-XSRF-TOKEN 헤더에 실어 보낸다. 인증 없이 호출할 수 있다.
 */
@RestController
public class CsrfController {

	/**
	 * CSRF 토큰 쿠키(XSRF-TOKEN)를 내려준다(API 명세 §3.1).
	 *
	 * <p>Spring Security 6 이후 CSRF 토큰은 지연 생성이라, 아무도 값을 읽지 않으면 쿠키가 발급되지 않는다.
	 * 파라미터로 받은 토큰의 getToken()을 호출해 값을 실제로 꺼내야 Set-Cookie가 응답에 붙는다.
	 * 본문에는 토큰을 싣지 않는다(쿠키로만 전달, data는 null).
	 */
	@GetMapping("/api/auth/csrf")
	public ApiResponse<Void> csrf(CsrfToken csrfToken) {
		csrfToken.getToken();
		return ApiResponse.success();
	}
}
