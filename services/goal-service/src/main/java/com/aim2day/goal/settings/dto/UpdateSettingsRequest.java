package com.aim2day.goal.settings.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.DayOfWeek;

/** weekStartDay에 없는 요일이 오면 JSON 역직렬화에서 실패해 400 MALFORMED_REQUEST가 된다. */
public record UpdateSettingsRequest(
		@NotNull DayOfWeek weekStartDay,
		@NotBlank String timezone,
		@NotNull Long version) {
}
