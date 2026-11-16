package com.aim2day.user.auth;

import com.aim2day.user.auth.dto.EmailCodeRequest;
import com.aim2day.user.auth.dto.EmailCodeResponse;
import com.aim2day.user.auth.dto.EmailCodeVerifyRequest;
import com.aim2day.user.auth.dto.EmailCodeVerifyResponse;
import com.aim2day.user.auth.dto.SignupRequest;
import com.aim2day.user.auth.dto.SignupResponse;
import com.aim2day.user.auth.dto.LoginRequest;
import com.aim2day.user.auth.dto.LoginResponse;
import com.aim2day.user.common.response.ApiResponse;
import com.aim2day.user.common.security.SessionUserIdAuthenticationFilter;
import com.aim2day.user.user.User;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.web.http.CookieSerializer;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

	private final EmailCodeService emailCodeService;
	private final SignupService signupService;
	private final LoginService loginService;
	private final CookieSerializer cookieSerializer;

	public AuthController(EmailCodeService emailCodeService, SignupService signupService, LoginService loginService,
			CookieSerializer cookieSerializer) {
		this.cookieSerializer = cookieSerializer;
		this.emailCodeService = emailCodeService;
		this.signupService = signupService;
		this.loginService = loginService;
	}

	@PostMapping("/email-codes")
	public ApiResponse<EmailCodeResponse> requestCode(@Valid @RequestBody EmailCodeRequest request) {
		return ApiResponse.success(emailCodeService.requestCode(request.email()));
	}

	@PostMapping("/email-codes/verify")
	public ApiResponse<EmailCodeVerifyResponse> verifyCode(@Valid @RequestBody EmailCodeVerifyRequest request) {
		return ApiResponse.success(emailCodeService.verifyCode(request.email(), request.code()));
	}

	@PostMapping("/signup")
	@ResponseStatus(HttpStatus.CREATED)
	public ApiResponse<SignupResponse> signup(@Valid @RequestBody SignupRequest request) {
		return ApiResponse.success(signupService.signup(request));
	}

	/**
	 * 로그인. 인증 성공 뒤에만 세션을 건드린다(실패 요청이 세션을 만들지 않게 한다).
	 *
	 * <p>SecurityConfig에서 sessionManagement를 꺼 두었으므로 Spring Security가 세션 고정 방어(로그인 시 세션 ID 교체)를
	 * 해 주지 않는다. 그래서 여기서 직접 한다. 로그인 전에 공격자가 심어 둔 세션 ID를 피해자가 그대로 쓰게 되면 로그인 뒤에도
	 * 공격자가 같은 ID로 접근할 수 있으므로, 기존 세션이 있으면 invalidate()로 폐기하고
	 * 새 세션을 만든다(ID뿐 아니라 이전 속성도 넘기지 않는다).
	 *
	 * <p>세션에는 계약(공유 계약 §4)대로 userId(Long)와 PRINCIPAL_NAME_INDEX_NAME(userId 문자열)만 저장한다.
	 * 인덱스 속성은 비밀번호 재설정 때 findByPrincipalName(userId)로 그 사용자의 모든 세션을 찾아 지우는 데 쓰인다.
	 * SecurityContext 등 객체는 넣지 않는다(goal-service가 역직렬화하지 못할 수 있다).
	 */
	@PostMapping("/login")
	public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest body, HttpServletRequest request) {
		User user = loginService.authenticate(body.email(), body.password());

		// changeSessionId()는 ID만 바꾸고 이전 세션의 속성을 그대로 넘긴다. 로그인 전에 공격자가 심은 속성이
		// 로그인 뒤 세션에 남지 않도록, 기존 세션은 폐기(저장소에서 삭제)하고 완전히 새 세션을 만든다.
		// 새 ID가 발급되므로 세션 고정 방어는 그대로 유지된다.
		HttpSession existing = request.getSession(false);
		if (existing != null) {
			existing.invalidate();
		}
		HttpSession session = request.getSession(true);
		session.setAttribute(SessionUserIdAuthenticationFilter.USER_ID_ATTRIBUTE, user.getId());
		session.setAttribute(FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME, String.valueOf(user.getId()));
		return ApiResponse.success(new LoginResponse(user.getId(), user.getEmail(), user.getUsername()));
	}

	/**
	 * 로그아웃. invalidate()가 저장소에서 세션을 즉시 지우고, Spring Session이 같은 응답에 SESSION 쿠키를
	 * Max-Age=0으로 내려 브라우저 쿠키도 만료시킨다. 세션이 없어도 200(멱등)이다. getSession(false)로 확인해
	 * 세션 없는 요청이 로그아웃하면서 새 세션을 만드는 일을 막는다.
	 *
	 * <p>세션이 이미 만료·삭제되었는데 브라우저가 SESSION 쿠키를 아직 갖고 있으면 getSession(false)는 null이라
	 * Spring Session이 쿠키를 지우지 않는다. 그러면 죽은 쿠키가 남으므로, 요청에 SESSION 쿠키가 있는 경우에 한해
	 * 설정된 CookieSerializer 빈으로 빈 값을 써서 같은 이름·Path·속성의 Max-Age=0 쿠키를 직접 내려준다.
	 * 새 세션은 만들지 않는다.
	 */
	@PostMapping("/logout")
	public ApiResponse<Void> logout(HttpServletRequest request, HttpServletResponse response) {
		HttpSession session = request.getSession(false);
		if (session != null) {
			session.invalidate();
		}
		else if (!cookieSerializer.readCookieValues(request).isEmpty()) {
			cookieSerializer.writeCookieValue(new CookieSerializer.CookieValue(request, response, ""));
		}
		return ApiResponse.success();
	}
}
