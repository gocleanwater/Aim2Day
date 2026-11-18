package com.aim2day.goal.reports.dto;

/** 직접 회고 저장 본문. PUT은 전체 저장이라 빠진 필드는 null로 저장한다(2026-11-18 사용자 결정). */
public record PutReviewRequest(String improvements, String nextFocus, Long version) {
}
