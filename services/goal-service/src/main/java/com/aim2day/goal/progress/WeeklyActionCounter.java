package com.aim2day.goal.progress;

import java.util.Collection;
import java.util.Map;

/**
 * 체크형 주간 목표의 (완료 행동 수, 분모)를 알려 주는 곳. 진행률 엔진은 이 인터페이스에만 의존한다.
 * 집계 규칙: 완료 행동(삭제 포함) / (미삭제 행동 + 삭제된 완료 행동). 구현은 JpaWeeklyActionCounter다.
 */
public interface WeeklyActionCounter {

	ActionCounts counts(long weeklyGoalId);

	/** 주간 목표 여러 개를 한 쿼리로 센다. 결과에는 요청한 모든 id가 들어 있고, 행동이 없는 주간은 (0,0)이다. */
	Map<Long, ActionCounts> countsFor(Collection<Long> weeklyGoalIds);
}
