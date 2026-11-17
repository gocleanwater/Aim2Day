package com.aim2day.goal.actions.dto;

/** 완료 상태 지정. completed는 필수이고 memo는 진행 기록의 메모다(최대 255자, 생략하면 서버가 채운다). */
public record CompletionRequest(Boolean completed, String memo) {
}
