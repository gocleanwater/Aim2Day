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
}
