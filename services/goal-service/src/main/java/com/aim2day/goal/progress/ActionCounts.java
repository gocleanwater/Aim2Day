package com.aim2day.goal.progress;

/** 주간 목표에 연결된 행동 수. total은 진행률의 분모(체크형), completed는 분자다. */
public record ActionCounts(int completed, int total) {
}
