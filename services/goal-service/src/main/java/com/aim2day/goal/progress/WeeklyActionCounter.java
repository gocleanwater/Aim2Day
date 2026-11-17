package com.aim2day.goal.progress;

/**
 * 체크형 주간 목표의 (완료 행동 수, 분모)를 알려 주는 곳. 행동 테이블은 단위 4에서 생기므로
 * 진행률 엔진은 이 인터페이스에만 의존한다. 단위 4가 실제 집계 구현으로 NoActionCounter를 대체한다.
 * 집계 규칙: 완료 행동(삭제 포함) / (미삭제 행동 + 삭제된 완료 행동).
 */
public interface WeeklyActionCounter {

	ActionCounts counts(long weeklyGoalId);
}
