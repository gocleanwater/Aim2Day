package com.aim2day.goal.onboarding;

import com.aim2day.goal.common.response.ApiResponse;
import com.aim2day.goal.onboarding.dto.OnboardingDtos.CompleteRequest;
import com.aim2day.goal.onboarding.dto.OnboardingDtos.CompleteResponse;
import com.aim2day.goal.onboarding.dto.OnboardingDtos.DraftResponse;
import com.aim2day.goal.onboarding.dto.OnboardingDtos.PutDraftRequest;
import com.aim2day.goal.onboarding.dto.OnboardingDtos.StatusResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 온보딩 API(§4.2). 경로에 리소스 id가 없고 항상 세션의 userId로 행을 찾으므로
 * 다른 사용자의 임시 저장에는 접근할 수 없다(소유권 검증이 구조적으로 보장된다).
 */
@RestController
@RequestMapping("/api/goals/onboarding")
public class OnboardingController {

	private final OnboardingStatusService statusService;
	private final OnboardingDraftService draftService;
	private final OnboardingCompleteService completeService;

	public OnboardingController(OnboardingStatusService statusService, OnboardingDraftService draftService,
			OnboardingCompleteService completeService) {
		this.statusService = statusService;
		this.draftService = draftService;
		this.completeService = completeService;
	}

	/**
	 * GET /api/goals/onboarding/status: 온보딩을 마쳤는지(연간 목표 보유 여부 등)를 돌려준다.
	 * 응답: 200과 StatusResponse. 프런트가 첫 화면을 온보딩으로 보낼지 정하는 데 쓴다.
	 */
	@GetMapping("/status")
	public ApiResponse<StatusResponse> status(@AuthenticationPrincipal Long userId) {
		return ApiResponse.success(statusService.status(userId));
	}

	/**
	 * GET /api/goals/onboarding/draft: 온보딩 임시 저장본을 조회한다.
	 * 응답: 200과 DraftResponse. 저장본이 없으면 빈 상태를 돌려준다.
	 */
	@GetMapping("/draft")
	public ApiResponse<DraftResponse> getDraft(@AuthenticationPrincipal Long userId) {
		return ApiResponse.success(draftService.get(userId));
	}

	/**
	 * PUT /api/goals/onboarding/draft: 온보딩 임시 저장본을 덮어쓴다(사용자당 한 행).
	 * 요청값: currentStep(단계 번호)·draftData(화면 입력 JSON 문자열, 서버는 해석하지 않음)·version(저장본이 이미 있을 때)을 담은 PutDraftRequest.
	 * 응답: 200과 저장된 DraftResponse. 형식·크기 오류는 400 VALIDATION_FAILED, 오래된 화면의 저장은 409 VERSION_CONFLICT.
	 */
	@PutMapping("/draft")
	public ApiResponse<DraftResponse> putDraft(@AuthenticationPrincipal Long userId,
			@RequestBody PutDraftRequest request) {
		return ApiResponse.success(draftService.put(userId, request));
	}

	/**
	 * DELETE /api/goals/onboarding/draft: 임시 저장본을 삭제한다.
	 * 응답: 200(본문 없음). 저장본이 없어도 성공한다.
	 */
	@DeleteMapping("/draft")
	public ApiResponse<Void> deleteDraft(@AuthenticationPrincipal Long userId) {
		draftService.delete(userId);
		return ApiResponse.success();
	}

	/**
	 * POST /api/goals/onboarding/complete: 온보딩 입력으로 연·월·주간 목표를 한 번에 만들고 온보딩을 마친다.
	 * 처리: 서비스가 하나의 트랜잭션에서 모든 목표를 저장하고 임시 저장본을 지운다. 중간에 실패하면 전부 되돌린다.
	 * 응답: 201과 CompleteResponse. 입력 오류는 400 VALIDATION_FAILED, 연간 목표 한도 초과는 409 GOAL_LIMIT_EXCEEDED.
	 */
	@PostMapping("/complete")
	@ResponseStatus(HttpStatus.CREATED)
	public ApiResponse<CompleteResponse> complete(@AuthenticationPrincipal Long userId,
			@RequestBody CompleteRequest request) {
		return ApiResponse.success(completeService.complete(userId, request));
	}
}
