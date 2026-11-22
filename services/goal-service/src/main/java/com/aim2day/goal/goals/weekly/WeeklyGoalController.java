package com.aim2day.goal.goals.weekly;

import com.aim2day.goal.common.response.ApiResponse;
import com.aim2day.goal.goals.weekly.dto.WeeklyGoalDtos.CreateWeeklyGoalRequest;
import com.aim2day.goal.goals.weekly.dto.WeeklyGoalDtos.PreviousSummaryResponse;
import com.aim2day.goal.goals.weekly.dto.WeeklyGoalDtos.UpdateWeeklyGoalRequest;
import com.aim2day.goal.goals.weekly.dto.WeeklyGoalDtos.WeeklyGoalDetailResponse;
import com.aim2day.goal.goals.weekly.dto.WeeklyGoalDtos.WeeklyGoalResponse;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 주간 목표 API(§4.6, 삭제 제외). 세션의 userId를 서비스에 넘기고, 서비스가 소유권을 확인한다. */
@RestController
@RequestMapping("/api/goals/weekly")
public class WeeklyGoalController {

	private final WeeklyGoalService service;
	private final PreviousSummaryService previousSummaryService;

	public WeeklyGoalController(WeeklyGoalService service, PreviousSummaryService previousSummaryService) {
		this.service = service;
		this.previousSummaryService = previousSummaryService;
	}

	/**
	 * GET /api/goals/weekly: 날짜가 속한 주의 주간 목표 목록을 조회한다(§4.6).
	 * 요청값: date(생략하면 사용자 기준 오늘), monthlyGoalId(상위 월간 목표 필터, 선택).
	 * 응답: 200과 주간 목표 목록. 형식 오류는 400 VALIDATION_FAILED.
	 */
	@GetMapping
	public ApiResponse<List<WeeklyGoalResponse>> list(@AuthenticationPrincipal Long userId,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
			@RequestParam(required = false) Long monthlyGoalId) {
		return ApiResponse.success(service.list(userId, date, monthlyGoalId));
	}

	/**
	 * POST /api/goals/weekly: 주간 목표를 만든다. 주간 목표는 현재 주로만 만들 수 있다(결정 5).
	 * 요청값: 상위 월간 목표 id·제목·측정 방식(체크형/수치형)·가중치·목표값 등 CreateWeeklyGoalRequest.
	 * 응답: 201과 만들어진 목표. 입력 오류는 400, 상위 목표 사용 불가는 422 GOAL_PARENT_UNAVAILABLE, 기간 불일치는 422 GOAL_PARENT_PERIOD_MISMATCH.
	 */
	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public ApiResponse<WeeklyGoalResponse> create(@AuthenticationPrincipal Long userId,
			@RequestBody CreateWeeklyGoalRequest request) {
		return ApiResponse.success(service.create(userId, request));
	}

	/** 고정 경로라 /{id}보다 먼저 매칭된다(Spring은 더 구체적인 패턴을 우선한다). */
	/**
	 * GET /api/goals/weekly/previous-summary: 지난주 주간 목표의 요약(달성 정도)을 돌려준다.
	 * 응답: 200과 PreviousSummaryResponse.
	 */
	@GetMapping("/previous-summary")
	public ApiResponse<PreviousSummaryResponse> previousSummary(@AuthenticationPrincipal Long userId) {
		return ApiResponse.success(previousSummaryService.summary(userId));
	}

	/**
	 * GET /api/goals/weekly/{id}: 주간 목표 상세를 조회한다.
	 * 응답: 200. 없거나 남의 목표이거나 삭제된 목표면 404 RESOURCE_NOT_FOUND.
	 */
	@GetMapping("/{id}")
	public ApiResponse<WeeklyGoalDetailResponse> detail(@AuthenticationPrincipal Long userId,
			@PathVariable long id) {
		return ApiResponse.success(service.detail(userId, id));
	}

	/**
	 * PATCH /api/goals/weekly/{id}: 주간 목표를 부분 수정한다.
	 * 요청값: 수정할 필드(상위 월간 목표·가중치·목표값 포함)와 version. 측정 방식은 바꿀 수 없다.
	 * 처리: 가중치·목표값·상위 목표가 바뀌면 진행률을 다시 계산한다.
	 * 응답: 200과 수정된 목표. 없거나 남의 목표면 404, 동시 수정은 409 VERSION_CONFLICT, 측정 방식 변경 시도는 422 GOAL_MEASUREMENT_TYPE_IMMUTABLE.
	 */
	@PatchMapping("/{id}")
	public ApiResponse<WeeklyGoalResponse> update(@AuthenticationPrincipal Long userId, @PathVariable long id,
			@RequestBody UpdateWeeklyGoalRequest request) {
		return ApiResponse.success(service.update(userId, id, request));
	}
}
