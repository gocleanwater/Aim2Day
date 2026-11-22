package com.aim2day.user.user;

import com.aim2day.user.common.response.ApiResponse;
import com.aim2day.user.common.response.BusinessException;
import com.aim2day.user.common.response.ErrorCode;
import com.aim2day.user.user.dto.MeResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 로그인한 사용자 본인의 계정 정보 API(/api/users/**). 이 경로는 SecurityConfig에서 인증이 필요하다.
 * 인증은 SessionUserIdAuthenticationFilter가 세션의 userId로 처리하므로 컨트롤러는 principal(userId)만 받는다.
 */
@RestController
@RequestMapping("/api/users")
public class UserController {

	private final UserRepository userRepository;

	/** 사용자 조회용 리포지토리를 주입받는다. */
	public UserController(UserRepository userRepository) {
		this.userRepository = userRepository;
	}

	/**
	 * GET /api/users/me. 현재 세션 사용자의 {id, email, username, createdAt}을 돌려준다. 응답 200, 오류 401 UNAUTHENTICATED.
	 * 비밀번호 해시 등 민감 필드는 응답 DTO(MeResponse)에 없어 내보내지 않는다.
	 * 세션 없음은 보안 설정이 401로 막으므로 여기 도달하면 userId가 있다.
	 * 세션은 살아 있는데 DB에 사용자가 없는 경우(계정이 삭제된 뒤 남은 세션 등)는 그 세션을 즉시 무효화하고 401로 답한다.
	 * 세션을 남겨 두면 이후 요청도 계속 "인증된 사용자"로 통과해 존재하지 않는 계정의 세션이 만료까지 살아 있게 된다.
	 */
	@GetMapping("/me")
	public ApiResponse<MeResponse> me(@AuthenticationPrincipal Long userId, HttpServletRequest request) {
		User user = userRepository.findById(userId).orElse(null);
		if (user == null) {
			HttpSession session = request.getSession(false);
			if (session != null) {
				session.invalidate();
			}
			throw new BusinessException(ErrorCode.UNAUTHENTICATED);
		}
		// DB 일시는 UTC로 읽은 LocalDateTime이다. Instant로 바꿔 ISO-8601 "...Z"로 내보낸다(API 명세 §2.3).
		// 명세 예시처럼 초 단위로 맞춘다.
		return ApiResponse.success(new MeResponse(user.getId(), user.getEmail(), user.getUsername(),
				user.getCreatedAt().toInstant(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS)));
	}
}
