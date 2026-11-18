package com.aim2day.user.auth;

import com.aim2day.user.auth.dto.AuthDtos.PasswordResetRequest;
import com.aim2day.user.auth.token.AuthTokenStore;
import com.aim2day.user.auth.token.TokenCodec;
import com.aim2day.user.common.response.BusinessException;
import com.aim2day.user.common.response.ErrorCode;
import com.aim2day.user.mail.AuthMailSender;
import com.aim2day.user.user.User;
import com.aim2day.user.user.UserRepository;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.stereotype.Service;

/**
 * 비밀번호 재설정(FN-AUTH-04): 링크 요청과 새 비밀번호 제출.
 * 클래스에 @Transactional을 걸지 않는다. 메일 전송(느린 외부 호출)과 BCrypt(느린 CPU 작업)가 DB 커넥션을 붙잡지 않게
 * 하려는 것이고, DB 접근은 조회 한 번과 saveAndFlush 한 번(각자 자체 트랜잭션)뿐이다.
 */
@Service
public class PasswordResetService {

	private static final Logger log = LoggerFactory.getLogger(PasswordResetService.class);

	/**
	 * 토큰을 쿼리(?token=)가 아니라 프래그먼트(#token=)에 싣는 이유: 브라우저는 '#' 뒤를 서버로 보내지 않으므로
	 * Nginx 접근 로그·프록시 로그·Referer에 토큰이 남지 않는다(비밀값 로그 금지). 프론트엔드가 JS로 읽어 본문으로 제출한다.
	 */
	private static final String RESET_PATH = "/reset-password.html#token=";

	private final UserRepository userRepository;
	private final PasswordEncoder passwordEncoder;
	private final AuthTokenStore tokenStore;
	private final AuthMailSender mailSender;
	private final FindByIndexNameSessionRepository<? extends Session> sessionRepository;
	private final String baseUrl;

	public PasswordResetService(UserRepository userRepository, PasswordEncoder passwordEncoder,
			AuthTokenStore tokenStore, AuthMailSender mailSender,
			FindByIndexNameSessionRepository<? extends Session> sessionRepository,
			@Value("${app.base-url}") String baseUrl) {
		this.userRepository = userRepository;
		this.passwordEncoder = passwordEncoder;
		this.tokenStore = tokenStore;
		this.mailSender = mailSender;
		this.sessionRepository = sessionRepository;
		// 끝의 '/'를 지워 "http://host//reset-password.html"처럼 이중 슬래시 링크가 만들어지지 않게 한다.
		this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
	}

	/**
	 * 재설정 링크 요청. 순서: 입력 검증(컨트롤러) -> 재요청 제한(429) -> 가입 여부 -> 토큰 발급 -> 메일 전송.
	 *
	 * <p>제한을 가입 여부 확인보다 먼저, 가입 여부와 무관하게 건다. 그래야 "60초 안에 두 번째 요청이 429인가"로
	 * 계정 존재 여부를 알아낼 수 없고, 가입 여부와 관계없이 응답이 같다(명세: 가입 여부와 상관없이 200).
	 * 가입 인증번호 요청(EmailCodeService)은 409로 가입 여부를 이미 드러내므로 순서가 반대다. 이쪽은 숨기는 API다.
	 */
	public void requestReset(String email) {
		// 이번 요청의 고유 ID를 제한 키의 값으로 저장한다(가입 인증번호 요청과 같은 방식).
		String ownerId = UUID.randomUUID().toString();
		long retryAfter = tokenStore.acquireMailThrottle(AuthTokenStore.PURPOSE_RESET, email, ownerId);
		if (retryAfter > 0) {
			throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS, Map.of("retryAfterSeconds", retryAfter));
		}

		User user = userRepository.findByEmail(email).orElse(null);
		if (user == null) {
			// 가입되지 않은 이메일: 메일도 토큰도 없이 같은 200으로 끝낸다. 제한 키는 이미 걸려 있어 응답 차이가 없다.
			return;
		}

		String token = TokenCodec.newToken();
		// 이전 토큰 삭제와 새 토큰 저장이 한 번에 원자적으로 일어난다(Lua). 마지막 링크만 유효하다.
		// 저장소 예외도 삼켜 같은 200으로 답한다(결정 15와 같은 이유): 가입 계정에만 저장소 호출이 있으므로 여기서 예외가
		// 500으로 새면 "500이면 가입 계정"이라는 응답 차이가 생긴다. 로그에는 사용자 ID와 예외 클래스명만 남긴다.
		boolean issued;
		try {
			issued = tokenStore.issueResetToken(user.getId(), token, email, ownerId);
		}
		catch (RuntimeException e) {
			log.warn("비밀번호 재설정 토큰 발급 실패 userId={} cause={}", user.getId(), e.getClass().getSimpleName());
			return;
		}
		if (!issued) {
			// 제한 키의 소유권을 잃었다(발급 전에 60초 넘게 멈춘 사이 더 최근 요청이 제한을 얻음). 그 요청의 링크를
			// 지키려고 토큰 교체와 메일을 모두 건너뛰고 같은 200으로 끝낸다. 응답으로 구별할 수 없다.
			log.info("비밀번호 재설정 요청이 더 최근 요청에 밀려 건너뜀 userId={}", user.getId());
			return;
		}
		try {
			mailSender.sendPasswordResetLink(email, baseUrl + RESET_PATH + token);
		}
		catch (RuntimeException e) {
			// 전송 실패를 응답에 드러내지 않는다(결정 15). 502를 주면 "계정이 있는 이메일에만 502"가 되어 가입 여부가 드러난다.
			// 로그에는 사용자 ID와 예외 클래스명만 남긴다. 이메일·토큰·예외 메시지(수신 주소가 들어갈 수 있음)는 남기지 않는다.
			log.warn("비밀번호 재설정 메일 전송 실패 userId={} cause={}", user.getId(), e.getClass().getSimpleName());
			// 제한 키는 풀지 않는다. 푸는 쪽이 "있는 계정만 재시도가 즉시 허용된다"는 새로운 차이를 만들고,
			// 명세도 "사용자는 60초 뒤 다시 요청한다"고 정했다. 받을 수 없는 링크의 토큰은 쓸모가 없으니 지우되,
			// 그 사이 더 새 토큰이 발급됐다면 건드리지 않도록 "아직 최신일 때만" 지운다(저장소가 원자적으로 판단).
			try {
				tokenStore.releaseResetTokenIfLatest(user.getId(), token);
			}
			catch (RuntimeException cleanupError) {
				log.warn("재설정 토큰 정리 실패 userId={} cause={}", user.getId(), cleanupError.getClass().getSimpleName());
			}
		}
	}

	/**
	 * 새 비밀번호 저장. 비밀번호 규칙·확인 일치는 컨트롤러의 @Valid가 이미 통과시켰으므로(= 토큰 소비보다 먼저),
	 * 규칙 위반 요청은 여기에 오지 않고 토큰도 소비되지 않는다.
	 */
	public long resetPassword(PasswordResetRequest request) {
		// 토큰은 한 번만 소비된다(저장소가 원자적으로 읽고 지운다). 없음·만료·이미 사용·변조를 구분하지 않는다.
		long userId = tokenStore.consumeResetToken(request.token())
				.orElseThrow(() -> new BusinessException(ErrorCode.AUTH_INVALID_TOKEN));

		// 토큰 발급 뒤 계정이 지워진 경우도 같은 오류로 답한다(토큰 상태를 추측할 단서를 주지 않는다).
		User user = userRepository.findById(userId)
				.orElseThrow(() -> new BusinessException(ErrorCode.AUTH_INVALID_TOKEN));
		user.changePasswordHash(passwordEncoder.encode(request.newPassword()));
		userRepository.saveAndFlush(user);

		// saveAndFlush는 서비스에 트랜잭션이 없으므로 저장소 메서드 자체의 트랜잭션이고, 메서드가 반환할 때 이미 커밋돼 있다.
		// 따라서 아래 세션 삭제는 항상 "비밀번호 변경 커밋 이후"에 실행된다. 이 순서가 로그인 쪽 재확인(LoginService의
		// 해시 재조회)과 짝을 이룬다: 삭제 목록에 못 든 늦은 로그인은 저장 직후 재조회에서 새 해시를 보고 스스로 세션을 지운다.
		// 나중에 이 메서드에 @Transactional을 붙이면 커밋이 세션 삭제 뒤로 밀리므로 붙이지 않는다.
		// 비밀번호를 바꾼 이유가 "남이 알게 됐을지 모른다"일 수 있으므로 기존 세션을 모두 끝낸다(다른 기기 포함, 결정 14).
		// 로그인이 세션에 넣어 둔 PRINCIPAL_NAME_INDEX_NAME(userId 문자열) 인덱스로 그 사용자의 세션을 모두 찾는다.
		// 비밀번호를 먼저 저장한 뒤 세션을 지우는 순서다. 반대면 세션만 끊기고 비밀번호는 안 바뀐 채 끝날 수 있다.
		// 이 요청에 세션 쿠키가 실려 있어도 저장소에서는 같이 지워진다. 다만 이 요청의 세션 객체를 이미 필터가 읽어 두었다면
		// 응답을 내보낼 때 Spring Session이 그 객체를 다시 저장해 되살릴 수 있으므로, 컨트롤러가 요청 세션도 invalidate한다.
		sessionRepository.findByPrincipalName(String.valueOf(userId)).keySet()
				.forEach(sessionRepository::deleteById);
		return userId;
	}
}
