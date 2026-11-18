package com.aim2day.goal.goals.criteria;

import com.aim2day.goal.common.response.ApiResponse;
import com.aim2day.goal.goals.criteria.dto.CriteriaDtos.AchievementRequest;
import com.aim2day.goal.goals.criteria.dto.CriteriaDtos.AchievementResponse;
import com.aim2day.goal.goals.criteria.dto.CriteriaDtos.ConfirmCriteriaRequest;
import com.aim2day.goal.goals.criteria.dto.CriteriaDtos.CriteriaResponse;
import com.aim2day.goal.goals.criteria.dto.CriteriaDtos.SaveCriteriaRequest;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 달성 기준 API(§4.4). 모든 메서드가 세션의 userId를 서비스에 넘기고 서비스가 소유권을 확인한다. */
@RestController
@RequestMapping("/api/goals/yearly/{id}/criteria")
public class CriteriaController {

	private final CriteriaService service;

	public CriteriaController(CriteriaService service) {
		this.service = service;
	}

	@GetMapping
	public ApiResponse<CriteriaResponse> get(@AuthenticationPrincipal Long userId, @PathVariable long id) {
		return ApiResponse.success(service.get(userId, id));
	}

	@PutMapping
	public ApiResponse<CriteriaResponse> save(@AuthenticationPrincipal Long userId, @PathVariable long id,
			@RequestBody SaveCriteriaRequest request) {
		return ApiResponse.success(service.save(userId, id, request));
	}

	@PostMapping("/confirm")
	public ApiResponse<CriteriaResponse> confirm(@AuthenticationPrincipal Long userId, @PathVariable long id,
			@RequestBody ConfirmCriteriaRequest request) {
		return ApiResponse.success(service.confirm(userId, id, request));
	}

	@PutMapping("/{criterionId}/achievement")
	public ApiResponse<AchievementResponse> achieve(@AuthenticationPrincipal Long userId, @PathVariable long id,
			@PathVariable long criterionId, @Valid @RequestBody AchievementRequest request) {
		return ApiResponse.success(service.achieve(userId, id, criterionId, request));
	}
}
