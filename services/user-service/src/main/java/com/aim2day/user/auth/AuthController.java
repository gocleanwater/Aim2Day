package com.aim2day.user.auth;

import com.aim2day.user.auth.dto.EmailCodeRequest;
import com.aim2day.user.auth.dto.EmailCodeResponse;
import com.aim2day.user.auth.dto.EmailCodeVerifyRequest;
import com.aim2day.user.auth.dto.EmailCodeVerifyResponse;
import com.aim2day.user.auth.dto.PasswordResetLinkRequest;
import com.aim2day.user.auth.dto.PasswordResetRequest;
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
	private final PasswordResetService passwordResetService;

	public AuthController(EmailCodeService emailCodeService, SignupService signupService, LoginService loginService,
			CookieSerializer cookieSerializer, PasswordResetService passwordResetService) {
		this.passwordResetService = passwordResetService;
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

	/** 재설정 링크 요청. 가입 여부와 관계없이 200 data:null이다(가입된 계정에만 메일을 보낸다). */
	@PostMapping("/password-reset-requests")
	public ApiResponse<Void> requestPasswordReset(@Valid @RequestBody PasswordResetLinkRequest request) {
		passwordResetService.requestReset(request.email());
		return ApiResponse.success();
	}

	/** 새 비밀번호 제출. @Valid가 토큰 소비보다 먼저 비밀번호 규칙·확인 일치를 검사한다. */
	@PostMapping("/password-reset")
	public ApiResponse<Void> resetPassword(@Valid @RequestBody PasswordResetRequest request,
			HttpServletRequest httpRequest) {
		long userId = passwordResetService.resetPassword(request);

		// 서비스가 저장소에서 그 사용자의 세션을 모두 지웠지만, 이 요청이 그 사용자의 세션 쿠키를 싣고 왔다면
		// SessionUserIdAuthenticationFilter가 이미 세션 객체를 읽어 요청에 붙여 둔 상태다. Spring Session은 응답을
		// 내보낼 때 요청에 붙은 세션을 다시 저장하므로, 그대로 두면 방금 지운 세션이 되살아난다. invalidate()로
		// "이 요청의 세션은 끝났다"고 표시해 재저장을 막고 쿠키도 만료시킨다(로그아웃과 같은 방식).
		// 다른 사용자의 세션 쿠키라면 건드리지 않는다. 세션이 없으면 getSession(false)가 null이라 새로 만들지 않는다.
		HttpSession session = httpRequest.getSession(false);
		if (session != null && String.valueOf(userId)
				.equals(session.getAttribute(FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME))) {
			session.invalidate();
		}
		return ApiResponse.success();
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
		// 순서가 중요하다. flushMode=IMMEDIATE(SessionConfig)라 각 setAttribute가 곧바로 Redis에 저장된다.
		// 인덱스 속성을 먼저 넣는 이유: 이 세션이 "그 사용자의 세션"으로 조회돼야 겹친 재설정이 삭제 대상에 넣을 수 있다.
		// 아직 userId 속성이 없는 중간 상태의 세션은 인증을 통과시키지 못하므로 노출돼도 안전하다.
		// 두 setAttribute도 try 안에 둔다. IMMEDIATE라 각 호출이 Redis에 쓰므로, userId 저장이 반영된 직후(또는 저장
		// 도중) 예외가 나면 이미 인증 가능한 세션이 저장소에 남을 수 있다. 어느 지점에서 실패해도 아래 catch가 폐기한다.
		try {
			session.setAttribute(FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME,
					String.valueOf(user.getId()));
			session.setAttribute(SessionUserIdAuthenticationFilter.USER_ID_ATTRIBUTE, user.getId());
			// 세션이 Redis에 저장된 뒤 DB 해시를 다시 읽는다(LoginService 설명 참고). 비밀번호 확인과 세션 저장 사이에
			// 재설정이 끝났다면 여기서 걸러 이전 비밀번호로 만든 세션이 남지 않게 한다.
			loginService.ensurePasswordUnchanged(user);
		}
		catch (RuntimeException | Error e) {
			// 불일치뿐 아니라 저장소·DB 오류 등 어떤 실패에서도 방금 저장한 세션을 폐기(Redis 삭제)한다. 확인되지 않은 세션을
			// 남기지 않는다. invalidate 뒤에는 Spring Session이 새 쿠키를 내리지 않고 만료 쿠키만 내려 로그인 쿠키가 발급되지 않는다.
			// 정리 자체가 실패해도(예: 저장소 장애) 원래 예외가 응답·로그에 남아야 하므로 suppressed로 붙이고 원래 예외를 던진다.
			try {
				session.invalidate();
			}
			catch (RuntimeException cleanupFailure) {
				e.addSuppressed(cleanupFailure);
			}
			throw e;
		}
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
