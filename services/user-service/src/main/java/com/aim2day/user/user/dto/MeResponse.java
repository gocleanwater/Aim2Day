package com.aim2day.user.user.dto;

import java.time.Instant;

/** createdAt은 Instant로 내보내 ISO-8601 UTC("...Z")로 직렬화된다(API 명세 §2.3). */
public record MeResponse(Long id, String email, String username, Instant createdAt) {
}
