package com.aim2day.goal.quadrant;

import com.aim2day.goal.common.response.ApiResponse;
import com.aim2day.goal.quadrant.dto.ConfirmResponse;
import com.aim2day.goal.quadrant.dto.PlacementRequest;
import com.aim2day.goal.quadrant.dto.PlacementResponse;
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

	@PutMapping("/{id}/placement")
	public ApiResponse<PlacementResponse> place(@AuthenticationPrincipal Long userId, @PathVariable long id,
			@RequestBody PlacementRequest request) {
		return ApiResponse.success(service.place(userId, id, request));
	}

	@DeleteMapping("/{id}/placement/pending")
	public ApiResponse<PlacementResponse> cancelPending(@AuthenticationPrincipal Long userId,
			@PathVariable long id) {
		return ApiResponse.success(service.cancelPending(userId, id));
	}

	@PostMapping("/placements/confirm")
	public ApiResponse<ConfirmResponse> confirm(@AuthenticationPrincipal Long userId) {
		return ApiResponse.success(service.confirm(userId));
	}
}
