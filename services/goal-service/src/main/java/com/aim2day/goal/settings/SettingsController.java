package com.aim2day.goal.settings;

import com.aim2day.goal.common.response.ApiResponse;
import com.aim2day.goal.settings.dto.SettingsResponse;
import com.aim2day.goal.settings.dto.UpdateSettingsRequest;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 설정 API(§4.1). 경로에 사용자 ID가 없고 항상 세션의 userId로 행을 찾으므로
 * 다른 사용자의 설정에는 접근할 수 없다(소유권 검증이 구조적으로 보장된다).
 */
@RestController
@RequestMapping("/api/settings")
public class SettingsController {

	private final SettingsService service;

	public SettingsController(SettingsService service) {
		this.service = service;
	}

	@GetMapping
	public ApiResponse<SettingsResponse> get(@AuthenticationPrincipal Long userId) {
		UserSetting setting = service.getOrCreate(userId);
		return ApiResponse.success(SettingsResponse.of(setting, service.toUserTime(setting)));
	}

	@PutMapping
	public ApiResponse<SettingsResponse> put(@AuthenticationPrincipal Long userId,
			@Valid @RequestBody UpdateSettingsRequest request) {
		UserSetting setting = service.update(userId, request);
		return ApiResponse.success(SettingsResponse.of(setting, service.toUserTime(setting)));
	}
}
