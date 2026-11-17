package com.aim2day.goal.goals.weekly;

import com.aim2day.goal.common.response.ApiResponse;
import com.aim2day.goal.goals.weekly.dto.CreateWeeklyGoalRequest;
import com.aim2day.goal.goals.weekly.dto.PreviousSummaryResponse;
import com.aim2day.goal.goals.weekly.dto.UpdateWeeklyGoalRequest;
import com.aim2day.goal.goals.weekly.dto.WeeklyGoalDetailResponse;
import com.aim2day.goal.goals.weekly.dto.WeeklyGoalResponse;
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

	@GetMapping
	public ApiResponse<List<WeeklyGoalResponse>> list(@AuthenticationPrincipal Long userId,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
			@RequestParam(required = false) Long monthlyGoalId) {
		return ApiResponse.success(service.list(userId, date, monthlyGoalId));
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public ApiResponse<WeeklyGoalResponse> create(@AuthenticationPrincipal Long userId,
			@RequestBody CreateWeeklyGoalRequest request) {
		return ApiResponse.success(service.create(userId, request));
	}

	/** 고정 경로라 /{id}보다 먼저 매칭된다(Spring은 더 구체적인 패턴을 우선한다). */
	@GetMapping("/previous-summary")
	public ApiResponse<PreviousSummaryResponse> previousSummary(@AuthenticationPrincipal Long userId) {
		return ApiResponse.success(previousSummaryService.summary(userId));
	}

	@GetMapping("/{id}")
	public ApiResponse<WeeklyGoalDetailResponse> detail(@AuthenticationPrincipal Long userId,
			@PathVariable long id) {
		return ApiResponse.success(service.detail(userId, id));
	}

	@PatchMapping("/{id}")
	public ApiResponse<WeeklyGoalResponse> update(@AuthenticationPrincipal Long userId, @PathVariable long id,
			@RequestBody UpdateWeeklyGoalRequest request) {
		return ApiResponse.success(service.update(userId, id, request));
	}
}
