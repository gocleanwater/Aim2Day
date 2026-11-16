package com.aim2day.user.auth;

import com.aim2day.user.common.response.BusinessException;
import com.aim2day.user.common.response.ErrorCode;
import com.aim2day.user.user.User;
import com.aim2day.user.user.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * 이메일·비밀번호를 검증한다(FN-AUTH-03). 세션 처리는 컨트롤러가 맡는다(HTTP 요청 객체가 필요하므로).
 * 로그인 실패 횟수 제한은 명세에 없어 두지 않았다.
 */
@Service
public class LoginService {

	private final UserRepository userRepository;
	private final PasswordEncoder passwordEncoder;
	/**
	 * 존재하지 않는 이메일일 때 비교할 더미 해시. BCrypt 비교는 수십 ms가 걸리는데, 사용자가 없을 때 이를 건너뛰면
	 * "응답이 빠르면 없는 계정"이라는 시간차로 가입 여부를 알아낼 수 있다. 같은 인코더로 같은 강도의 해시를 미리 만들어 두고
	 * 사용자가 없을 때도 matches를 한 번 수행해 두 경로의 소요 시간을 비슷하게 맞춘다. 값은 어떤 비밀번호와도 맞지 않는 임의 문자열이다.
	 */
	private final String dummyHash;

	public LoginService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
		this.userRepository = userRepository;
		this.passwordEncoder = passwordEncoder;
		this.dummyHash = passwordEncoder.encode(java.util.UUID.randomUUID().toString());
	}

	/** 성공하면 사용자를, 이메일 없음·비밀번호 불일치는 구분 없이 같은 예외를 던진다(계정 존재 여부 은닉). */
	public User authenticate(String email, String password) {
		User user = userRepository.findByEmail(email).orElse(null);
		// 사용자가 없어도 반드시 matches를 한 번 호출한다(위 dummyHash 설명). 결과는 버린다.
		boolean matches = passwordEncoder.matches(password, user != null ? user.getPasswordHash() : dummyHash);
		if (user == null || !matches) {
			throw new BusinessException(ErrorCode.AUTH_INVALID_CREDENTIALS);
		}
		return user;
	}

	/**
	 * 세션을 저장한 뒤 호출하는 재확인. DB의 비밀번호 해시를 새로 읽어 authenticate 때 본 해시와 같은지 본다.
	 * 다르면(그 사이 비밀번호 재설정이 커밋됨, 계정이 삭제된 경우 포함) 같은 AUTH_INVALID_CREDENTIALS를 던진다.
	 *
	 * <p>필요한 이유: 재설정은 "비밀번호 변경 커밋 -> 그 사용자 세션 삭제" 순서다. 로그인이 이전 비밀번호를 확인하고 세션을
	 * 저장하는 사이에 재설정이 끝나면 그 세션은 삭제 목록에 없어 살아남는다. 세션을 먼저 저장하고 나서 해시를 읽으면
	 * 어떻게 겹쳐도 둘 중 하나가 걸린다: (a) 재설정 커밋이 이 조회보다 앞서면 여기서 해시 차이를 보고, (b) 뒤에 오면
	 * 재설정의 세션 삭제는 커밋 이후라 이미 저장된 이 세션을 찾아 지운다. 호출자는 예외를 받으면 방금 만든 세션을 폐기한다.
	 * 해시 문자열 비교는 같은 사용자의 저장된 BCrypt 해시끼리의 비교라 타이밍 공격 대상이 아니다.
	 */
	public void ensurePasswordUnchanged(User authenticated) {
		String current = userRepository.findPasswordHashById(authenticated.getId()).orElse(null);
		if (current == null || !current.equals(authenticated.getPasswordHash())) {
			throw new BusinessException(ErrorCode.AUTH_INVALID_CREDENTIALS);
		}
	}
}
