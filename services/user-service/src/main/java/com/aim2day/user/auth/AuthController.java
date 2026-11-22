package com.aim2day.user.auth;

import com.aim2day.user.auth.dto.AuthDtos.EmailCodeRequest;
import com.aim2day.user.auth.dto.AuthDtos.EmailCodeResponse;
import com.aim2day.user.auth.dto.AuthDtos.EmailCodeVerifyRequest;
import com.aim2day.user.auth.dto.AuthDtos.EmailCodeVerifyResponse;
import com.aim2day.user.auth.dto.AuthDtos.PasswordResetLinkRequest;
import com.aim2day.user.auth.dto.AuthDtos.PasswordResetRequest;
import com.aim2day.user.auth.dto.AuthDtos.SignupRequest;
import com.aim2day.user.auth.dto.AuthDtos.LoginRequest;
import com.aim2day.user.auth.dto.AuthDtos.LoginResponse;
import com.aim2day.user.common.response.ApiResponse;
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

/**
 * 인증 API 진입점(/api/auth/**). 프록시가 이 경로를 user-service로 보내고, 브라우저(프론트엔드)가 호출한다.
 * 컨트롤러는 HTTP 입출력(요청 본문 검증, 세션·쿠키, 응답 래핑)만 맡고 업무 규칙은 서비스에 위임한다.
 * 제공 API: 인증번호 요청·확인, 가입, 로그인, 로그아웃, 비밀번호 재설정 링크 요청·재설정(API 명세 §3.1).
 * 성공 응답은 {code, message, data}(ApiResponse)이고, 실패는 BusinessException·검증 예외를 GlobalExceptionHandler가
 * {code, message, details}로 바꾼다. 이 경로는 SecurityConfig에서 인증 없이 열려 있지만 CSRF 검사는 받는다.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

	private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AuthController.class);

	private final EmailCodeService emailCodeService;
	private final SignupService signupService;
	private final LoginService loginService;
	private final CookieSerializer cookieSerializer;
	private final PasswordResetService passwordResetService;
	private final LoginSessionStarter loginSessionStarter;

	/** 협력 객체를 주입받는다. CookieSerializer는 로그아웃 때 죽은 세션 쿠키를 지우는 데 쓴다(SessionConfig의 빈). */
	public AuthController(EmailCodeService emailCodeService, SignupService signupService, LoginService loginService,
			CookieSerializer cookieSerializer, PasswordResetService passwordResetService,
			LoginSessionStarter loginSessionStarter) {
		this.loginSessionStarter = loginSessionStarter;
		this.passwordResetService = passwordResetService;
		this.cookieSerializer = cookieSerializer;
		this.emailCodeService = emailCodeService;
		this.signupService = signupService;
		this.loginService = loginService;
	}

	/**
	 * POST /api/auth/email-codes. 가입용 이메일 인증번호를 발급해 메일로 보낸다(FN-AUTH-01).
	 * 요청: {email}(정규화·형식 검증은 DTO와 @Valid). 응답 200: {expiresInSeconds}(번호 유효 시간, 600초).
	 * 오류: 400 VALIDATION_FAILED, 409 AUTH_EMAIL_EXISTS(이미 가입), 429 TOO_MANY_REQUESTS(60초 재요청 제한,
	 * details.retryAfterSeconds), 502 AUTH_MAIL_SEND_FAILED. 처리 순서와 경쟁 처리는 EmailCodeService에 있다.
	 */
	@PostMapping("/email-codes")
	public ApiResponse<EmailCodeResponse> requestCode(@Valid @RequestBody EmailCodeRequest request) {
		return ApiResponse.success(emailCodeService.requestCode(request.email()));
	}

	/**
	 * POST /api/auth/email-codes/verify. 이메일과 6자리 인증번호를 확인하고 가입권(signupToken)을 발급한다.
	 * 요청: {email, code}. 응답 200: {signupToken, expiresInSeconds}(가입권 유효 시간, 1800초).
	 * 오류: 400 VALIDATION_FAILED, 400 AUTH_INVALID_CODE(오답, details.remainingAttempts), 400 AUTH_CODE_EXPIRED
	 * (없음·만료·5회 실패). 가입권은 이 응답 본문으로만 전달되며 이후 /signup에서 한 번만 쓸 수 있다.
	 */
	@PostMapping("/email-codes/verify")
	public ApiResponse<EmailCodeVerifyResponse> verifyCode(@Valid @RequestBody EmailCodeVerifyRequest request) {
		return ApiResponse.success(emailCodeService.verifyCode(request.email(), request.code()));
	}

	/**
	 * POST /api/auth/signup. 가입권으로 계정을 만들고 곧바로 로그인 상태(세션)로 만든다(FN-AUTH-02).
	 * 요청: {signupToken, username, password, passwordConfirm}. 응답 201: {id, email, username}.
	 * 오류: 400 VALIDATION_FAILED(비밀번호 규칙·확인 불일치 등), 400 AUTH_INVALID_TOKEN(가입권 없음·만료·사용됨),
	 * 409 AUTH_EMAIL_EXISTS. 처리 흐름: 계정 생성(서비스, 커밋 완료) -> 세션 생성 순서이며, 세션 생성이 실패해도 가입은 성공으로 본다.
	 */
	@PostMapping("/signup")
	@ResponseStatus(HttpStatus.CREATED)
	public ApiResponse<LoginResponse> signup(@Valid @RequestBody SignupRequest body, HttpServletRequest request) {
		// 계정 생성은 서비스 안에서 자체 트랜잭션으로 커밋까지 끝나고 돌아온다. 세션은 그 뒤에 만들므로
		// 커밋되지 않은(롤백될) 계정의 세션이 생기지 않는다. 실패(토큰·검증·이메일 중복)는 예외로 여기 오기 전에 끝나 세션을 만들지 않는다.
		User user = signupService.signup(body);
		// 계정 커밋 뒤 세션 저장 전에 비밀번호 재설정이 끝날 수 있다(재설정 링크는 가입 전에도 받을 수 있다). 그러면 재설정 시점
		// 이전에 만든 세션이 남게 되므로, 로그인과 똑같이 세션 저장 뒤 DB 해시를 다시 읽어 확인한다(불일치 시 세션 폐기).
		try {
			loginSessionStarter.start(request, user, () -> loginService.ensurePasswordUnchanged(user));
		}
		catch (RuntimeException e) {
			// 정책: 계정은 이미 커밋됐으므로 가입 자체는 성공(201)으로 응답하고 세션만 없이 내보낸다(SESSION 쿠키 없음, 사용자는 로그인하면 된다).
			// 세션 폐기는 LoginSessionStarter가 이미 했다. 사용자 ID만 남기고 예외 메시지·이메일·비밀값은 로그에 넣지 않는다.
			log.warn("가입 직후 자동 로그인 세션을 만들지 못해 세션 없이 응답한다. userId={}, cause={}", user.getId(),
					e.getClass().getSimpleName());
		}
		return ApiResponse.success(new LoginResponse(user.getId(), user.getEmail(), user.getUsername()));
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
		// 세션 ID 재발급·속성 저장·실패 시 폐기는 가입 자동 로그인과 공용인 LoginSessionStarter가 한다.
		// 세션이 저장된 뒤 DB 해시를 다시 읽어(LoginService 설명 참고) 비밀번호 확인과 세션 저장 사이에 끝난 재설정을 걸러 낸다.
		loginSessionStarter.start(request, user, () -> loginService.ensurePasswordUnchanged(user));
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
