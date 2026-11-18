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

	@GetMapping
	public ApiResponse<YearlyGoalListResponse> list(@AuthenticationPrincipal Long userId,
			@RequestParam(required = false) Integer year) {
		return ApiResponse.success(service.list(userId, year));
	}

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

	@GetMapping("/{id}")
	public ApiResponse<YearlyGoalDetailResponse> detail(@AuthenticationPrincipal Long userId,
			@PathVariable long id) {
		return ApiResponse.success(service.detail(userId, id));
	}

	@PatchMapping("/{id}")
	public ApiResponse<YearlyGoalResponse> update(@AuthenticationPrincipal Long userId, @PathVariable long id,
			@RequestBody UpdateYearlyGoalRequest request) {
		return ApiResponse.success(service.update(userId, id, request));
	}
}
