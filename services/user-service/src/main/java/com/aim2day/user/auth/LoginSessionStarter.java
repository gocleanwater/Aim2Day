package com.aim2day.user.auth;

import com.aim2day.user.common.security.SessionUserIdAuthenticationFilter;
import com.aim2day.user.user.User;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.stereotype.Component;

/**
 * 인증이 끝난 사용자에게 로그인 세션을 만든다. 로그인(POST /api/auth/login)과 가입 직후 자동 로그인(POST /api/auth/signup)이
 * 같은 경로를 쓰도록 한 곳에 모았다.
 *
 * <p>SecurityConfig에서 sessionManagement를 꺼 두었으므로 Spring Security가 세션 고정 방어(로그인 시 세션 ID 교체)를
 * 해 주지 않는다. 그래서 여기서 직접 한다. 로그인 전에 공격자가 심어 둔 세션 ID를 피해자가 그대로 쓰게 되면 로그인 뒤에도
 * 공격자가 같은 ID로 접근할 수 있으므로, 기존 세션이 있으면 invalidate()로 폐기하고 새 세션을 만든다
 * (changeSessionId()는 ID만 바꾸고 이전 속성을 넘기므로 쓰지 않는다).
 *
 * <p>세션에는 계약(공유 계약 §4)대로 userId(Long)와 PRINCIPAL_NAME_INDEX_NAME(userId 문자열)만 저장한다.
 * 인덱스 속성은 비밀번호 재설정 때 findByPrincipalName(userId)로 그 사용자의 모든 세션을 찾아 지우는 데 쓰인다.
 * SecurityContext 등 객체는 넣지 않는다(goal-service가 역직렬화하지 못할 수 있다).
 * CSRF 토큰은 쿠키 저장소(서버 상태 없음)라 로그인·가입 때 다시 발급하지 않는다.
 */
@Component
public class LoginSessionStarter {

	/**
	 * @param afterStored 세션이 저장된 직후 실행하는 확인(없으면 null). 예외를 던지면 방금 만든 세션을 폐기하고 그 예외를 다시 던진다.
	 */
	public void start(HttpServletRequest request, User user, Runnable afterStored) {
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
			// 로그인은 여기서 DB 해시를 다시 읽어 동시 비밀번호 재설정과의 경합을 거른다(LoginService 참고).
			// 방금 만든 계정은 재설정 대상이 될 수 없어 가입은 null을 넘긴다.
			if (afterStored != null) {
				afterStored.run();
			}
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
	}
}
