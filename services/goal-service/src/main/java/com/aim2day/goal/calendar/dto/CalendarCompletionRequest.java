package com.aim2day.goal.calendar.dto;

/** 완료 상태 지정. completed는 필수이고 version은 받지 않는다(상태를 지정하는 요청은 멱등이다, API 명세 결정 2). */
public record CalendarCompletionRequest(Boolean completed) {
}
