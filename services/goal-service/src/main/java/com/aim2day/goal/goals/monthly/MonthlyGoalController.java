package com.aim2day.goal.goals.monthly;

import com.aim2day.goal.common.response.ApiResponse;
import com.aim2day.goal.goals.monthly.dto.MonthlyGoalDtos.CreateMonthlyGoalRequest;
import com.aim2day.goal.goals.monthly.dto.MonthlyGoalDtos.MonthlyGoalDetailResponse;
import com.aim2day.goal.goals.monthly.dto.MonthlyGoalDtos.MonthlyGoalResponse;
import com.aim2day.goal.goals.monthly.dto.MonthlyGoalDtos.UpdateMonthlyGoalRequest;
import java.util.List;
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

/** 월간 목표 API(§4.5, 삭제 제외). 세션의 userId를 서비스에 넘기고, 서비스가 소유권을 조회 조건으로 확인한다. */
@RestController
@RequestMapping("/api/goals/monthly")
public class MonthlyGoalController {

	private final MonthlyGoalService service;

	public MonthlyGoalController(MonthlyGoalService service) {
		this.service = service;
	}

	/**
	 * GET /api/goals/monthly: 월간 목표 목록을 조회한다(§4.5).
	 * 요청값: month(YYYY-MM, 선택), yearlyGoalId(상위 연간 목표 필터, 선택).
	 * 응답: 200과 월간 목표 목록. 형식 오류는 400 VALIDATION_FAILED.
	 */
	@GetMapping
	public ApiResponse<List<MonthlyGoalResponse>> list(@AuthenticationPrincipal Long userId,
			@RequestParam(required = false) String month, @RequestParam(required = false) Long yearlyGoalId) {
		return ApiResponse.success(service.list(userId, month, yearlyGoalId));
	}

	/**
	 * POST /api/goals/monthly: 월간 목표를 만든다.
	 * 요청값: 상위 연간 목표 id·제목·설명·색 등 CreateMonthlyGoalRequest. 월간 목표는 계정 시간대 기준 현재 달로 만들어진다.
	 * 처리: 상위 연간 목표가 현재 사용자의 삭제되지 않은 목표인지, 올해 목표라 기간이 겹치는지 확인한 뒤 저장한다.
	 * 응답: 201과 만들어진 목표. 입력 오류는 400, 상위 목표 사용 불가는 422 GOAL_PARENT_UNAVAILABLE, 기간 불일치는 422 GOAL_PARENT_PERIOD_MISMATCH.
	 */
	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public ApiResponse<MonthlyGoalResponse> create(@AuthenticationPrincipal Long userId,
			@RequestBody CreateMonthlyGoalRequest request) {
		return ApiResponse.success(service.create(userId, request));
	}

	/**
	 * GET /api/goals/monthly/{id}: 월간 목표 상세를 조회한다.
	 * 응답: 200. 없거나 남의 목표이거나 삭제된 목표면 404 RESOURCE_NOT_FOUND.
	 */
	@GetMapping("/{id}")
	public ApiResponse<MonthlyGoalDetailResponse> detail(@AuthenticationPrincipal Long userId,
			@PathVariable long id) {
		return ApiResponse.success(service.detail(userId, id));
	}

	/**
	 * PATCH /api/goals/monthly/{id}: 월간 목표를 부분 수정한다.
	 * 요청값: 수정할 필드와 version.
	 * 응답: 200과 수정된 목표. 없거나 남의 목표면 404 RESOURCE_NOT_FOUND, 동시 수정은 409 VERSION_CONFLICT, 입력 오류는 400 VALIDATION_FAILED.
	 */
	@PatchMapping("/{id}")
	public ApiResponse<MonthlyGoalResponse> update(@AuthenticationPrincipal Long userId, @PathVariable long id,
			@RequestBody UpdateMonthlyGoalRequest request) {
		return ApiResponse.success(service.update(userId, id, request));
	}
}
