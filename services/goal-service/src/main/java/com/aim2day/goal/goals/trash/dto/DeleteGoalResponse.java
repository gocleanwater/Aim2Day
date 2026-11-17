package com.aim2day.goal.goals.trash.dto;

import java.time.Instant;

/** 삭제 성공 응답(API 명세 §4.7). 복구 기한은 삭제 시각 + 30일이다. */
public record DeleteGoalResponse(Instant deletedAt, Instant restoreDeadline) {
}
