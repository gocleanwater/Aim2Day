package com.aim2day.goal.goals.monthly;

import com.aim2day.goal.common.response.ApiResponse;
import com.aim2day.goal.goals.monthly.dto.CreateMonthlyGoalRequest;
import com.aim2day.goal.goals.monthly.dto.MonthlyGoalDetailResponse;
import com.aim2day.goal.goals.monthly.dto.MonthlyGoalResponse;
import com.aim2day.goal.goals.monthly.dto.UpdateMonthlyGoalRequest;
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

	@GetMapping
	public ApiResponse<List<MonthlyGoalResponse>> list(@AuthenticationPrincipal Long userId,
			@RequestParam(required = false) String month, @RequestParam(required = false) Long yearlyGoalId) {
		return ApiResponse.success(service.list(userId, month, yearlyGoalId));
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public ApiResponse<MonthlyGoalResponse> create(@AuthenticationPrincipal Long userId,
			@RequestBody CreateMonthlyGoalRequest request) {
		return ApiResponse.success(service.create(userId, request));
	}

	@GetMapping("/{id}")
	public ApiResponse<MonthlyGoalDetailResponse> detail(@AuthenticationPrincipal Long userId,
			@PathVariable long id) {
		return ApiResponse.success(service.detail(userId, id));
	}

	@PatchMapping("/{id}")
	public ApiResponse<MonthlyGoalResponse> update(@AuthenticationPrincipal Long userId, @PathVariable long id,
			@RequestBody UpdateMonthlyGoalRequest request) {
		return ApiResponse.success(service.update(userId, id, request));
	}
}
