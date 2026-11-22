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

	/**
	 * GET /api/goals/yearly/{id}/criteria: 연간 목표의 달성 기준 목록과 확정 여부를 조회한다.
	 * 응답: 200. 목표가 없거나 남의 것이면 404 RESOURCE_NOT_FOUND.
	 */
	@GetMapping
	public ApiResponse<CriteriaResponse> get(@AuthenticationPrincipal Long userId, @PathVariable long id) {
		return ApiResponse.success(service.get(userId, id));
	}

	/**
	 * PUT /api/goals/yearly/{id}/criteria: 달성 기준 목록을 통째로 교체한다. 확정 전에는 초안 저장, 확정 후에는 개정이다.
	 * 요청값: 기준 항목(제목·배점 등)과 version을 담은 SaveCriteriaRequest.
	 * 응답: 200과 저장된 기준. 입력 오류는 400, 동시 수정은 409 VERSION_CONFLICT, 확정 후 개정에서 배점 합계가 100이 아니면 422 CRITERIA_WEIGHT_SUM_INVALID.
	 */
	@PutMapping
	public ApiResponse<CriteriaResponse> save(@AuthenticationPrincipal Long userId, @PathVariable long id,
			@RequestBody SaveCriteriaRequest request) {
		return ApiResponse.success(service.save(userId, id, request));
	}

	/**
	 * POST /api/goals/yearly/{id}/criteria/confirm: 달성 기준을 확정한다.
	 * 요청값: 클라이언트가 본 목표 version(goalVersion)과 확정 시점에 이미 달성한 기준 id 목록(baselineAchievedCriterionIds).
	 * 처리: 이미 확정된 기준이 아닌지, 배점 합계가 100인지 확인한 뒤 확정하고 최초 기준선(달성률)을 저장한다.
	 * 응답: 200과 확정된 기준. 이미 확정이면 409 CRITERIA_ALREADY_CONFIRMED, 동시 수정은 409 VERSION_CONFLICT, 합계가 100이 아니면 422 CRITERIA_WEIGHT_SUM_INVALID.
	 */
	@PostMapping("/confirm")
	public ApiResponse<CriteriaResponse> confirm(@AuthenticationPrincipal Long userId, @PathVariable long id,
			@RequestBody ConfirmCriteriaRequest request) {
		return ApiResponse.success(service.confirm(userId, id, request));
	}

	/**
	 * PUT /api/goals/yearly/{id}/criteria/{criterionId}/achievement: 기준 하나의 달성 여부를 지정한다(멱등).
	 * 요청값: 달성 여부 값(@Valid 검증).
	 * 처리: 확정된 기준에만 허용하고, 달성 배점을 합산해 연간 목표 진행률을 다시 계산한다.
	 * 응답: 200과 갱신된 달성 결과. 확정 전이면 422 CRITERIA_NOT_CONFIRMED, 없거나 남의 기준이면 404.
	 */
	@PutMapping("/{criterionId}/achievement")
	public ApiResponse<AchievementResponse> achieve(@AuthenticationPrincipal Long userId, @PathVariable long id,
			@PathVariable long criterionId, @Valid @RequestBody AchievementRequest request) {
		return ApiResponse.success(service.achieve(userId, id, criterionId, request));
	}
}
