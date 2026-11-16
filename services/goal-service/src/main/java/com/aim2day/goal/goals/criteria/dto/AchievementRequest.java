package com.aim2day.goal.goals.criteria.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 목표 상태를 지정하는 요청이라 version을 받지 않는다(API 명세 §2.4). */
public record AchievementRequest(@NotNull Boolean achieved, @Size(max = 255) String memo) {
}
