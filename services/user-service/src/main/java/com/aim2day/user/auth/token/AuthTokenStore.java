package com.aim2day.user.auth.token;

import java.util.Optional;

/**
 * 가입 인증번호·가입권·메일 재요청 제한 저장소. 운영은 Redis, 테스트는 메모리 구현을 쓴다.
 * 서비스는 이 인터페이스에만 의존한다. 구현체는 아래 메서드 각각이 동시 요청에서도 원자적으로 동작해야 한다.
 */
public interface AuthTokenStore {

	/** 메일 재요청 제한 목적 이름(키의 {purpose}). 재설정 메일은 별도 목적(reset)으로 따로 센다. */
	String PURPOSE_SIGNUP = "signup";

	/** 인증번호 유효 시간(10분)·최대 시도 횟수(5회)·가입권 유효 시간(30분)·재요청 간격(60초). */
	int CODE_TTL_SECONDS = 600;
	int MAX_ATTEMPTS = 5;
	int TICKET_TTL_SECONDS = 1800;
	int THROTTLE_SECONDS = 60;

	/** 인증번호 확인 결과. WRONG일 때만 remainingAttempts, OK일 때만 signupToken(가입권 원문)이 의미 있다. */
	enum CodeStatus { OK, WRONG, EXPIRED }

	record CodeCheck(CodeStatus status, int remainingAttempts, String signupToken) {
		public static CodeCheck ok(String signupToken) {
			return new CodeCheck(CodeStatus.OK, 0, signupToken);
		}

		public static CodeCheck wrong(int remaining) {
			return new CodeCheck(CodeStatus.WRONG, remaining, null);
		}

		public static CodeCheck expired() {
			return new CodeCheck(CodeStatus.EXPIRED, 0, null);
		}
	}

	/**
	 * 인증번호를 저장한다. 같은 이메일의 이전 번호와 시도 횟수는 한 번에 덮어써 마지막 번호만 유효하게 한다.
	 * 저장에는 번호의 해시만 쓴다. ownerId는 이번 발급 요청의 고유 ID로, 실패 정리 때 "내 것인지" 확인하는 데 쓴다.
	 * 가입 재요청 제한 키의 값이 ownerId일 때만 저장하고(검사와 저장은 원자적) 저장했으면 true를 돌려준다.
	 * 제한이 만료되어 다른 요청이 이미 제한을 얻었다면 아무것도 쓰지 않고 false를 돌려준다.
	 */
	boolean saveCode(String email, String code, String ownerId);

	/**
	 * 인증번호를 확인한다. 비교·시도 차감·성공 시 번호 삭제와 가입권 저장이 한 번에 원자적으로 일어난다.
	 * 맞는 번호는 (번호가 살아 있는 한) 남은 횟수와 무관하게 성공하고, 시도 횟수는 틀렸을 때만 차감한다.
	 * 성공하면 가입권 원문이 결과에 담긴다.
	 */
	CodeCheck verifyCode(String email, String code);

	/** 가입권을 한 번만 소비하고 이메일을 돌려준다(읽기와 삭제가 한 동작). 없거나 만료됐으면 비어 있다. */
	Optional<String> consumeTicket(String token);

	/**
	 * 메일 재요청 제한을 건다. 제한이 없던 상태에서 걸었으면 0, 이미 걸려 있으면 남은 초(1 이상)를 돌려준다.
	 * 제한 키의 값으로 ownerId를 저장해 누가 건 제한인지 알 수 있게 한다.
	 */
	long acquireMailThrottle(String purpose, String email, String ownerId);

	/** 재요청 제한의 남은 초(읽기 전용, 1 이상). 제한을 새로 걸지 않는다. */
	long mailThrottleRemainingSeconds(String purpose, String email);

	/**
	 * 발급 실패 정리. 제한 키의 값이 ownerId일 때만 제한을, 번호 레코드의 owner가 ownerId일 때만 번호를 지운다.
	 * 이전 요청이 늦게 실패해도 그 사이에 들어온 새 요청의 제한·번호는 건드리지 않는다. 둘은 한 번에 원자적으로 처리한다.
	 */
	void releaseIfOwner(String purpose, String email, String ownerId);
}
