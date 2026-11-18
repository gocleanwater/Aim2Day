package com.aim2day.goal.onboarding.dto;

import tools.jackson.databind.annotation.JsonDeserialize;

/**
 * 임시 저장 본문. draftData는 서버가 해석하지 않는 자유 JSON이라 JSON 텍스트로 받는다(RawJsonDeserializer).
 * 값 검증(단계 1~4, 필수, 64KB)은 서비스가 한다.
 */
public record PutDraftRequest(Integer currentStep, @JsonDeserialize(using = RawJsonDeserializer.class) String draftData,
		Long version) {
}
