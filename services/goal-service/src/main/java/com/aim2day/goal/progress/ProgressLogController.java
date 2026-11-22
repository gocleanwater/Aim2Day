package com.aim2day.goal.progress;

import com.aim2day.goal.common.exception.FieldErrors;
import com.aim2day.goal.common.exception.ValidationReason;
import com.aim2day.goal.common.response.ApiResponse;
import com.aim2day.goal.progress.dto.ProgressLogResponse;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 대시보드의 최근 진행 기록(§4.8). 조회 조건에 세션의 userId를 넣어 다른 사용자의 기록은 읽을 수 없다.
 */
@RestController
@RequestMapping("/api/goals/progress-logs")
public class ProgressLogController {

	static final int DEFAULT_LIMIT = 10;
	static final int MAX_LIMIT = 50;

	private final ProgressLogRepository repository;
	private final ProgressLogTitleResolver titleResolver;

	public ProgressLogController(ProgressLogRepository repository, ProgressLogTitleResolver titleResolver) {
		this.repository = repository;
		this.titleResolver = titleResolver;
	}

	/**
	 * GET /api/goals/progress-logs?limit=10: 최근 진행 기록을 최신순으로 조회한다.
	 * 요청값: limit(1~50, 기본 10).
	 * 처리: 세션 userId로 기록을 읽고, 목표 제목은 삭제된 목표까지 포함해 한꺼번에 찾아 붙인다.
	 * 응답: 200과 기록 목록. limit 범위를 벗어나면 400 VALIDATION_FAILED.
	 */
	@GetMapping
	public ApiResponse<List<ProgressLogResponse>> recent(@AuthenticationPrincipal Long userId,
			@RequestParam(defaultValue = "10") int limit) {
		if (limit < 1 || limit > MAX_LIMIT) {
			throw new FieldErrors().add("limit", ValidationReason.OUT_OF_RANGE).toException();
		}
		List<GoalProgressLog> logs = repository.findByUserIdOrderByCreatedAtDescIdDesc(userId, PageRequest.of(0, limit));
		Map<ProgressTargetType, Map<Long, String>> titles = titleResolver.resolve(logs);
		return ApiResponse.success(logs.stream()
				.map(log -> ProgressLogResponse.of(log,
						titles.getOrDefault(log.getTargetType(), Map.of()).get(log.getTargetId())))
				.toList());
	}
}
