package com.aim2day.goal.goals.yearly;

import com.aim2day.goal.common.response.ApiResponse;
import com.aim2day.goal.goals.yearly.dto.YearlyGoalDtos.CreateYearlyGoalRequest;
import com.aim2day.goal.goals.yearly.dto.YearlyGoalDtos.ReorderYearlyGoalsRequest;
import com.aim2day.goal.goals.yearly.dto.YearlyGoalDtos.UpdateYearlyGoalRequest;
import com.aim2day.goal.goals.yearly.dto.YearlyGoalDtos.YearlyGoalDetailResponse;
import com.aim2day.goal.goals.yearly.dto.YearlyGoalDtos.YearlyGoalListResponse;
import com.aim2day.goal.goals.yearly.dto.YearlyGoalDtos.YearlyGoalResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 연간 목표 API(§4.3, 삭제 제외). 모든 메서드가 세션의 userId를 서비스에 넘기고,
 * 서비스가 user_id 조건으로 조회하므로 다른 사용자의 목표에는 접근할 수 없다(404).
 */
@RestController
@RequestMapping("/api/goals/yearly")
public class YearlyGoalController {

	private final YearlyGoalService service;

	public YearlyGoalController(YearlyGoalService service) {
		this.service = service;
	}

	/**
	 * GET /api/goals/yearly: 해당 연도의 연간 목표 목록을 조회한다(§4.3).
	 * 요청값: year(생략하면 사용자 기준 올해).
	 * 응답: 200과 정렬 순서대로의 연간 목표 목록. year 형식 오류는 400 VALIDATION_FAILED.
	 */
	@GetMapping
	public ApiResponse<YearlyGoalListResponse> list(@AuthenticationPrincipal Long userId,
			@RequestParam(required = false) Integer year) {
		return ApiResponse.success(service.list(userId, year));
	}

	/**
	 * POST /api/goals/yearly: 연간 목표를 만든다.
	 * 요청값: 제목·연도 등 CreateYearlyGoalRequest(JSON 본문).
	 * 처리: 서비스가 입력을 검증하고, 사용자당 연도별 최대 3개 제한을 사용자 행 잠금 아래에서 확인한 뒤 저장한다.
	 * 응답: 201과 만들어진 목표. 입력 오류는 400 VALIDATION_FAILED, 한도 초과는 409 GOAL_LIMIT_EXCEEDED.
	 */
	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public ApiResponse<YearlyGoalResponse> create(@AuthenticationPrincipal Long userId,
			@RequestBody CreateYearlyGoalRequest request) {
		return ApiResponse.success(service.create(userId, request));
	}

	/** 경로 /order는 /{id}보다 구체적이라 먼저 매칭된다. */
	@PutMapping("/order")
	public ApiResponse<YearlyGoalListResponse> reorder(@AuthenticationPrincipal Long userId,
			@RequestBody ReorderYearlyGoalsRequest request) {
		return ApiResponse.success(service.reorder(userId, request));
	}

	/**
	 * GET /api/goals/yearly/{id}: 연간 목표 상세(달성 기준·진행률·하위 목표 요약)를 조회한다.
	 * 응답: 200. 없거나 남의 목표이거나 삭제된 목표면 404 RESOURCE_NOT_FOUND.
	 */
	@GetMapping("/{id}")
	public ApiResponse<YearlyGoalDetailResponse> detail(@AuthenticationPrincipal Long userId,
			@PathVariable long id) {
		return ApiResponse.success(service.detail(userId, id));
	}

	/**
	 * PATCH /api/goals/yearly/{id}: 연간 목표를 부분 수정한다.
	 * 요청값: 수정할 필드와 version(UpdateYearlyGoalRequest).
	 * 처리: 소유권을 확인하고 version으로 동시 수정을 막는다.
	 * 응답: 200과 수정된 목표. 없거나 남의 목표면 404 RESOURCE_NOT_FOUND, 동시 수정은 409 VERSION_CONFLICT, 입력 오류는 400 VALIDATION_FAILED.
	 */
	@PatchMapping("/{id}")
	public ApiResponse<YearlyGoalResponse> update(@AuthenticationPrincipal Long userId, @PathVariable long id,
			@RequestBody UpdateYearlyGoalRequest request) {
		return ApiResponse.success(service.update(userId, id, request));
	}
}
