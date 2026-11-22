package com.aim2day.user.user.dto;

import java.time.Instant;

/** GET /api/users/me 응답 data. 비밀번호 해시를 담지 않는다. createdAt은 Instant로 내보내 ISO-8601 UTC("...Z")로 직렬화된다(API 명세 §2.3). */
public record MeResponse(Long id, String email, String username, Instant createdAt) {
}
