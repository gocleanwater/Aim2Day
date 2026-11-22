package com.aim2day.goal.goals.weekly.record;

import com.aim2day.goal.common.response.ApiResponse;
import com.aim2day.goal.goals.weekly.record.dto.NumericRecordDtos.CreateNumericRecordRequest;
import com.aim2day.goal.goals.weekly.record.dto.NumericRecordDtos.NumericRecordCreatedResponse;
import com.aim2day.goal.goals.weekly.record.dto.NumericRecordDtos.NumericRecordDeletedResponse;
import com.aim2day.goal.goals.weekly.record.dto.NumericRecordDtos.NumericRecordResponse;
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

	/**
	 * GET /api/goals/weekly/{weeklyGoalId}/numeric-records: 수치형 주간 목표의 기록 목록을 조회한다.
	 * 응답: 200과 기록 목록. 주간 목표가 없거나 남의 것이면 404 RESOURCE_NOT_FOUND.
	 */
	@GetMapping
	public ApiResponse<List<NumericRecordResponse>> list(@AuthenticationPrincipal Long userId,
			@PathVariable long weeklyGoalId) {
		return ApiResponse.success(service.list(userId, weeklyGoalId));
	}

	/**
	 * POST /api/goals/weekly/{weeklyGoalId}/numeric-records: 수치 기록을 직접 추가한다.
	 * 요청값: amount(0보다 큰 기록량)·memo(선택)를 담은 CreateNumericRecordRequest.
	 * 처리: 서비스가 소유권과 수치형 여부를 확인하고 기록을 저장한 뒤 주간 목표 진행률을 갱신한다.
	 * 응답: 201과 기록·갱신된 진행률. 입력 오류는 400, 수치형이 아니면 422 GOAL_NOT_NUMERIC.
	 */
	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public ApiResponse<NumericRecordCreatedResponse> add(@AuthenticationPrincipal Long userId,
			@PathVariable long weeklyGoalId, @RequestBody CreateNumericRecordRequest request) {
		return ApiResponse.success(service.add(userId, weeklyGoalId, request));
	}

	/**
	 * DELETE /api/goals/weekly/{weeklyGoalId}/numeric-records/{recordId}: 수치 기록을 삭제한다.
	 * 처리: 소유권을 확인하고 삭제한 뒤 진행률을 다시 계산한다. 행동 완료로 생긴 기록은 삭제할 수 없다.
	 * 응답: 200과 갱신된 진행률. 없거나 남의 기록이면 404, 행동 완료 기록이면 422 RECORD_NOT_DELETABLE.
	 */
	@DeleteMapping("/{recordId}")
	public ApiResponse<NumericRecordDeletedResponse> delete(@AuthenticationPrincipal Long userId,
			@PathVariable long weeklyGoalId, @PathVariable long recordId) {
		return ApiResponse.success(service.delete(userId, weeklyGoalId, recordId));
	}
}
