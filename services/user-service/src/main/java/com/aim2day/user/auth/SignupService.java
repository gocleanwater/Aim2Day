package com.aim2day.user.auth;

import com.aim2day.user.auth.dto.AuthDtos.SignupRequest;
import com.aim2day.user.auth.token.AuthTokenStore;
import com.aim2day.user.common.response.BusinessException;
import com.aim2day.user.common.response.ErrorCode;
import com.aim2day.user.user.User;
import com.aim2day.user.user.UserRepository;
import java.sql.SQLException;
import java.util.Locale;
import java.util.regex.Pattern;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * 가입권으로 계정을 만든다(FN-AUTH-02).
 * 입력 검증(사용자명·비밀번호 규칙·확인 일치)은 컨트롤러의 @Valid가 이 메서드 호출 전에 끝낸다. 그래서 비밀번호 규칙을
 * 어긴 요청은 가입권을 소비하기 전에 400으로 끝나고, 사용자는 같은 가입권으로 다시 제출할 수 있다.
 * 클래스에 @Transactional을 걸지 않는다. 가입권 소비(Redis)와 BCrypt 해시(느린 CPU 작업)가 DB 커넥션을 붙잡지 않게 하고,
 * INSERT는 saveAndFlush 한 번의 자체 트랜잭션으로 끝난다.
 */
@Service
public class SignupService {

	/** 이메일 UNIQUE 제약 이름(Flyway V1). 이것만 "이미 가입"으로 취급한다. */
	private static final String EMAIL_UNIQUE_CONSTRAINT = "idx_users_email";

	private final UserRepository userRepository;
	private final PasswordEncoder passwordEncoder;
	private final AuthTokenStore tokenStore;

	public SignupService(UserRepository userRepository, PasswordEncoder passwordEncoder, AuthTokenStore tokenStore) {
		this.userRepository = userRepository;
		this.passwordEncoder = passwordEncoder;
		this.tokenStore = tokenStore;
	}

	/** 계정을 만들고(INSERT 커밋 완료) 저장된 사용자를 돌려준다. 자동 로그인 세션은 컨트롤러가 이 커밋 뒤에 만든다. */
	public User signup(SignupRequest request) {
		// 가입권은 GETDEL로 한 번만 소비된다. 없음·만료·이미 사용·변조를 구분하지 않고 같은 오류로 답한다.
		String email = tokenStore.consumeTicket(request.signupToken())
				.orElseThrow(() -> new BusinessException(ErrorCode.AUTH_INVALID_TOKEN));

		// 선조회는 흔한 중복(인증 후 가입 전에 다른 경로로 가입 완료)을 빨리 거르기 위한 것이다.
		// 동시 가입 경쟁은 아래 UNIQUE 제약이 최종적으로 막는다.
		if (userRepository.existsByEmail(email)) {
			throw new BusinessException(ErrorCode.AUTH_EMAIL_EXISTS);
		}

		User user = new User(email, request.username(), passwordEncoder.encode(request.password()));
		try {
			// saveAndFlush: 제약 위반을 이 호출 안에서 즉시 받아 409로 바꾸기 위해 INSERT를 바로 실행한다.
			user = userRepository.saveAndFlush(user);
		}
		catch (DataIntegrityViolationException e) {
			// 이메일 UNIQUE 위반만 409다. NOT NULL·길이 등 다른 무결성 오류는 우리 버그나 데이터 문제이므로
			// 409로 가리지 않고 다시 던져 500(INTERNAL_ERROR)으로 드러낸다.
			// 예외 메시지에는 이메일이 들어 있으므로 로그에 남기지 않는다(판별에만 쓴다).
			if (isEmailUniqueViolation(e)) {
				throw new BusinessException(ErrorCode.AUTH_EMAIL_EXISTS);
			}
			throw e;
		}
		return user;
	}

	/** MySQL 8: "Duplicate entry '...' for key 'users.idx_users_email'"(5.7은 접두사 없음). 키 이름을 정확히 일치시킨다. */
	private static final Pattern MYSQL_KEY = Pattern.compile("for key '(?:[^'.]+\\.)?" + EMAIL_UNIQUE_CONSTRAINT + "'",
			Pattern.CASE_INSENSITIVE);
	/** H2: Unique index or primary key violation: "PUBLIC.IDX_USERS_EMAIL INDEX PUBLIC.IDX_USERS_EMAIL_INDEX_4 ON PUBLIC.USERS(...)"(버전에 따라 INDEX 부분이 없다). */
	private static final Pattern H2_KEY = Pattern.compile(
			"\"(?:[^\".\\s]+\\.)?" + EMAIL_UNIQUE_CONSTRAINT + "(?:_index_\\d+)?\\s+(?:index\\s+\\S+\\s+)?on\\s", Pattern.CASE_INSENSITIVE);

	/**
	 * 원인 사슬에서 이메일 UNIQUE 제약 위반인지 판별한다.
	 * 1) Hibernate가 제약 이름을 주면 그것만 본다. 대소문자는 무시하고 MySQL이 붙이는 "users." 같은 접두사는 허용하되,
	 *    이름 자체는 정확히 일치해야 한다(부분 문자열이면 "idx_users_email_backup" 같은 다른 제약을 잘못 409로 보낸다).
	 * 2) 제약 이름을 못 받은 경우에만 DB 메시지를 본다. 그것도 실제 중복 키 오류(MySQL SQLState 23000 + errorCode 1062,
	 *    H2 SQLState 23505)인 SQLException의 메시지에서 정확한 키 이름 패턴이 맞을 때만 인정한다.
	 *    NOT NULL 위반처럼 메시지에 우연히 이 이름이 들어 있는 다른 무결성 오류는 409가 되지 않는다.
	 */
	static boolean isEmailUniqueViolation(Throwable error) {
		for (Throwable t = error; t != null; t = t.getCause() == t ? null : t.getCause()) {
			if (t instanceof ConstraintViolationException cve && cve.getConstraintName() != null
					&& isEmailConstraintName(cve.getConstraintName())) {
				return true;
			}
			if (t instanceof SQLException sql && isDuplicateKey(sql) && matchesKeyInMessage(sql.getMessage())) {
				return true;
			}
		}
		return false;
	}

	private static boolean isEmailConstraintName(String name) {
		String n = name.toLowerCase(Locale.ROOT).replace("`", "").replace("\"", "");
		int dot = n.lastIndexOf('.');
		if (dot >= 0) {
			n = n.substring(dot + 1);
		}
		return n.equals(EMAIL_UNIQUE_CONSTRAINT);
	}

	private static boolean isDuplicateKey(SQLException e) {
		return ("23000".equals(e.getSQLState()) && e.getErrorCode() == 1062) || "23505".equals(e.getSQLState());
	}

	private static boolean matchesKeyInMessage(String message) {
		return message != null && (MYSQL_KEY.matcher(message).find() || H2_KEY.matcher(message).find());
	}
}
