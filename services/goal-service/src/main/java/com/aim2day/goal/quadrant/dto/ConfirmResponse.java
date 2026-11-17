package com.aim2day.goal.quadrant.dto;

import java.time.LocalDate;

/** POST placements/confirm 응답(API 명세 §4.10). */
public record ConfirmResponse(LocalDate date, int confirmedCount, int unclassifiedCount) {
}
