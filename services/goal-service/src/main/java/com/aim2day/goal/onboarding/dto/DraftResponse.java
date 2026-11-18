package com.aim2day.goal.onboarding.dto;

import com.aim2day.goal.common.time.UtcTime;
import com.aim2day.goal.onboarding.OnboardingDraft;
import com.fasterxml.jackson.annotation.JsonRawValue;
import java.time.Instant;

/**
 * 임시 저장 응답. draftData는 DB가 돌려준 JSON 텍스트를 그대로 응답에 끼워 넣는다(@JsonRawValue).
 * 한 번 파싱해 다시 쓰면 큰 숫자·소수 정밀도가 double로 깎이므로, 저장한 값 그대로 돌려주려는 것이다.
 */
public record DraftResponse(int currentStep, @JsonRawValue String draftData, long version, Instant updatedAt) {

	public static DraftResponse of(OnboardingDraft draft) {
		return new DraftResponse(draft.getCurrentStep(), draft.getDraftData(), draft.getVersion(),
				UtcTime.toInstant(draft.getUpdatedAt()));
	}
}
