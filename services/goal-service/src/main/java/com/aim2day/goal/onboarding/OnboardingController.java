package com.aim2day.goal.onboarding;

import com.aim2day.goal.common.response.ApiResponse;
import com.aim2day.goal.onboarding.dto.CompleteRequest;
import com.aim2day.goal.onboarding.dto.CompleteResponse;
import com.aim2day.goal.onboarding.dto.DraftResponse;
import com.aim2day.goal.onboarding.dto.PutDraftRequest;
import com.aim2day.goal.onboarding.dto.StatusResponse;
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

	@GetMapping("/status")
	public ApiResponse<StatusResponse> status(@AuthenticationPrincipal Long userId) {
		return ApiResponse.success(statusService.status(userId));
	}

	@GetMapping("/draft")
	public ApiResponse<DraftResponse> getDraft(@AuthenticationPrincipal Long userId) {
		return ApiResponse.success(draftService.get(userId));
	}

	@PutMapping("/draft")
	public ApiResponse<DraftResponse> putDraft(@AuthenticationPrincipal Long userId,
			@RequestBody PutDraftRequest request) {
		return ApiResponse.success(draftService.put(userId, request));
	}

	@DeleteMapping("/draft")
	public ApiResponse<Void> deleteDraft(@AuthenticationPrincipal Long userId) {
		draftService.delete(userId);
		return ApiResponse.success();
	}

	@PostMapping("/complete")
	@ResponseStatus(HttpStatus.CREATED)
	public ApiResponse<CompleteResponse> complete(@AuthenticationPrincipal Long userId,
			@RequestBody CompleteRequest request) {
		return ApiResponse.success(completeService.complete(userId, request));
	}
}
