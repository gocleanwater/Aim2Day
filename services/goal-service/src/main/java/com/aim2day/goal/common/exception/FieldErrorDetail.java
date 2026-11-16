package com.aim2day.goal.common.exception;

/** VALIDATION_FAILED details.fieldErrors의 한 항목: { "field": "title", "reason": "TOO_LONG" }. */
public record FieldErrorDetail(String field, String reason) {
}
