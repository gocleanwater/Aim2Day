package com.aim2day.goal.quadrant;

import com.aim2day.goal.common.response.ApiResponse;
import com.aim2day.goal.quadrant.dto.QuadrantDtos.CancelAllResponse;
import com.aim2day.goal.quadrant.dto.QuadrantDtos.ConfirmResponse;
import com.aim2day.goal.quadrant.dto.QuadrantDtos.PlacementRequest;
import com.aim2day.goal.quadrant.dto.QuadrantDtos.PlacementResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 사분면 분류 API(§4.10). 세션의 userId를 서비스에 넘기고, 서비스가 소유권을 확인한다. */
@RestController
@RequestMapping("/api/actions")
public class QuadrantController {

	private final QuadrantService service;

	public QuadrantController(QuadrantService service) {
		this.service = service;
	}

	/**
	 * PUT /api/actions/{id}/placement: 행동을 사분면 좌표에 놓거나 트레이로 되돌린다(확정 전 변경으로 저장).
	 * 요청값: 중요도·긴급도 좌표 또는 트레이 지정(PlacementRequest).
	 * 응답: 200과 PlacementResponse. 오늘 수행일이 아니면 422 QUADRANT_DATE_INVALID, 목표 연결 행동이 중요도 50 미만이면 422 QUADRANT_OUT_OF_RANGE,
	 * 없거나 남의 행동이면 404 RESOURCE_NOT_FOUND.
	 */
	@PutMapping("/{id}/placement")
	public ApiResponse<PlacementResponse> place(@AuthenticationPrincipal Long userId, @PathVariable long id,
			@RequestBody PlacementRequest request) {
		return ApiResponse.success(service.place(userId, id, request));
	}

	/**
	 * DELETE /api/actions/{id}/placement/pending: 행동 하나의 확정되지 않은 변경을 취소한다.
	 * 응답: 200과 되돌려진 PlacementResponse. 없거나 남의 행동이면 404 RESOURCE_NOT_FOUND.
	 */
	@DeleteMapping("/{id}/placement/pending")
	public ApiResponse<PlacementResponse> cancelPending(@AuthenticationPrincipal Long userId,
			@PathVariable long id) {
		return ApiResponse.success(service.cancelPending(userId, id));
	}

	// 리터럴 경로(/placements/pending)와 /{id}/placement/pending은 세그먼트 구성이 달라 겹치지 않는다.
	/**
	 * DELETE /api/actions/placements/pending: 오늘의 확정되지 않은 변경 전체를 한 번에 취소한다([변경 취소], 결정 23).
	 * 응답: 200과 취소된 개수 등 CancelAllResponse.
	 */
	@DeleteMapping("/placements/pending")
	public ApiResponse<CancelAllResponse> cancelAllPending(@AuthenticationPrincipal Long userId) {
		return ApiResponse.success(service.cancelAllPending(userId));
	}

	/**
	 * POST /api/actions/placements/confirm: 오늘의 확정되지 않은 변경을 모두 확정한다.
	 * 처리: 확정 좌표는 다음에 다시 확정할 때까지 유지된다(결정 9).
	 * 응답: 200과 확정 결과 ConfirmResponse.
	 */
	@PostMapping("/placements/confirm")
	public ApiResponse<ConfirmResponse> confirm(@AuthenticationPrincipal Long userId) {
		return ApiResponse.success(service.confirm(userId));
	}
}
