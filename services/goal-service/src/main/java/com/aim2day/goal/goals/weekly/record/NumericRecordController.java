package com.aim2day.goal.goals.weekly.record;

import com.aim2day.goal.common.response.ApiResponse;
import com.aim2day.goal.goals.weekly.record.dto.CreateNumericRecordRequest;
import com.aim2day.goal.goals.weekly.record.dto.NumericRecordCreatedResponse;
import com.aim2day.goal.goals.weekly.record.dto.NumericRecordDeletedResponse;
import com.aim2day.goal.goals.weekly.record.dto.NumericRecordResponse;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 수치 기록 API(§4.6). 주간 목표의 소유권은 서비스가 세션의 userId로 확인한다. */
@RestController
@RequestMapping("/api/goals/weekly/{weeklyGoalId}/numeric-records")
public class NumericRecordController {

	private final NumericRecordService service;

	public NumericRecordController(NumericRecordService service) {
		this.service = service;
	}

	@GetMapping
	public ApiResponse<List<NumericRecordResponse>> list(@AuthenticationPrincipal Long userId,
			@PathVariable long weeklyGoalId) {
		return ApiResponse.success(service.list(userId, weeklyGoalId));
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public ApiResponse<NumericRecordCreatedResponse> add(@AuthenticationPrincipal Long userId,
			@PathVariable long weeklyGoalId, @RequestBody CreateNumericRecordRequest request) {
		return ApiResponse.success(service.add(userId, weeklyGoalId, request));
	}

	@DeleteMapping("/{recordId}")
	public ApiResponse<NumericRecordDeletedResponse> delete(@AuthenticationPrincipal Long userId,
			@PathVariable long weeklyGoalId, @PathVariable long recordId) {
		return ApiResponse.success(service.delete(userId, weeklyGoalId, recordId));
	}
}
