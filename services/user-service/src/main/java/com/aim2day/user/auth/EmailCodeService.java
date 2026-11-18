package com.aim2day.user.auth;

import com.aim2day.user.auth.dto.AuthDtos.EmailCodeResponse;
import com.aim2day.user.auth.dto.AuthDtos.EmailCodeVerifyResponse;
import com.aim2day.user.auth.token.AuthTokenStore;
import com.aim2day.user.auth.token.AuthTokenStore.CodeCheck;
import com.aim2day.user.auth.token.CodeGenerator;
import java.util.UUID;
import com.aim2day.user.common.response.BusinessException;
import com.aim2day.user.common.response.ErrorCode;
import com.aim2day.user.mail.AuthMailSender;
import com.aim2day.user.user.UserRepository;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 가입 인증번호 발급과 확인(FN-AUTH-01).
 * @Transactional을 걸지 않는다. 메일 전송(느린 외부 호출)이 DB 트랜잭션·커넥션을 붙잡지 않게 하려는 것이고,
 * 여기서 쓰는 DB 접근은 가입 여부 조회 한 번뿐이라 리포지토리 호출 단위 트랜잭션으로 충분하다.
 */
@Service
public class EmailCodeService {

	private static final Logger log = LoggerFactory.getLogger(EmailCodeService.class);

	private final UserRepository userRepository;
	private final AuthTokenStore tokenStore;
	private final AuthMailSender mailSender;
	private final CodeGenerator codeGenerator;

	public EmailCodeService(UserRepository userRepository, AuthTokenStore tokenStore, AuthMailSender mailSender,
			CodeGenerator codeGenerator) {
		this.codeGenerator = codeGenerator;
		this.userRepository = userRepository;
		this.tokenStore = tokenStore;
		this.mailSender = mailSender;
	}

	/** 처리 순서(명세): 입력 검증(컨트롤러) -> 가입 여부(409) -> 재요청 제한(429) -> 번호 저장 -> 메일 전송. */
	public EmailCodeResponse requestCode(String email) {
		// 가입 여부를 제한보다 먼저 본다. 이미 가입된 이메일은 메일을 보내지 않으므로 제한 키를 소모시키지 않는다.
		if (userRepository.existsByEmail(email)) {
			throw new BusinessException(ErrorCode.AUTH_EMAIL_EXISTS);
		}

		// 이번 발급 요청의 고유 ID. 재요청 제한 키의 값과 번호 레코드의 owner로 저장해 두었다가,
		// 이 요청이 실패했을 때 "내가 만든 것"만 정리하는 데 쓴다. (제한 60초가 지난 뒤 늦게 실패한 이전 요청이
		// 그 사이 들어온 새 요청의 번호·제한을 지우는 것을 막는다. 번호가 우연히 같아도 ID는 다르다.)
		String ownerId = UUID.randomUUID().toString();
		long retryAfter = tokenStore.acquireMailThrottle(AuthTokenStore.PURPOSE_SIGNUP, email, ownerId);
		if (retryAfter > 0) {
			throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS, Map.of("retryAfterSeconds", retryAfter));
		}

		String code = codeGenerator.newCode();
		boolean saved;
		try {
			saved = tokenStore.saveCode(email, code, ownerId);
		}
		catch (RuntimeException e) {
			// 저장소 장애로 번호를 못 만들었는데 제한만 남으면 사용자가 60초간 재시도할 수 없다. 내 제한을 풀고 원인은 그대로 던진다.
			tokenStore.releaseIfOwner(AuthTokenStore.PURPOSE_SIGNUP, email, ownerId);
			throw e;
		}
		if (!saved) {
			// 제한을 얻은 뒤 저장 전에 60초 넘게 지체해 제한이 만료됐고, 그 사이 다른 요청이 같은 이메일로 이미 번호를 발급했다.
			// 지금 저장하면 그 요청의 번호를 덮어써 사용자가 받은 메일의 번호가 무효가 되므로 저장도 메일 발송도 하지 않는다.
			// 제한은 이미 남의 것이라 정리할 것이 없다(releaseIfOwner도 부르지 않는다). 명세상 "재요청 제한 중"과 같은
			// 상황이므로 429와 남은 초(현재 제한 키 TTL)로 안내한다.
			throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS, Map.of("retryAfterSeconds",
					tokenStore.mailThrottleRemainingSeconds(AuthTokenStore.PURPOSE_SIGNUP, email)));
		}

		try {
			mailSender.sendSignupCode(email, code);
		}
		catch (RuntimeException e) {
			// 실패를 숨기지 않고 502로 알린다(결정 15). 이메일·번호는 로그에 남기지 않고 예외 종류만 남긴다.
			log.warn("가입 인증번호 메일 전송 실패 cause={}", e.getClass().getSimpleName());
			// 내 ID가 남아 있는 제한·번호만 한 번에 지운다. 그 사이 다른 요청이 새로 발급했다면 건드리지 않는다.
			tokenStore.releaseIfOwner(AuthTokenStore.PURPOSE_SIGNUP, email, ownerId);
			throw new BusinessException(ErrorCode.AUTH_MAIL_SEND_FAILED);
		}
		return new EmailCodeResponse(AuthTokenStore.CODE_TTL_SECONDS);
	}

	/**
	 * 번호가 맞으면 가입권을 발급한다. 비교·차감·번호 삭제·가입권 저장은 저장소가 한 번의 원자 연산으로 처리한다.
	 * 정답은 번호가 살아 있는 한 성공하고, 시도 횟수는 오답일 때만 줄어든다.
	 */
	public EmailCodeVerifyResponse verifyCode(String email, String code) {
		CodeCheck check = tokenStore.verifyCode(email, code);
		return switch (check.status()) {
			case OK -> new EmailCodeVerifyResponse(check.signupToken(), AuthTokenStore.TICKET_TTL_SECONDS);
			case WRONG -> throw new BusinessException(ErrorCode.AUTH_INVALID_CODE,
					Map.of("remainingAttempts", check.remainingAttempts()));
			// 없음·만료·5회 실패를 구분하지 않는다. 사용자가 할 일은 어느 경우든 새 번호 요청이다.
			case EXPIRED -> throw new BusinessException(ErrorCode.AUTH_CODE_EXPIRED);
		};
	}
}
