package com.aim2day.goal.progress;

import org.springframework.stereotype.Component;

/** 단위 3용 구현. 행동 테이블이 아직 없어 항상 (0, 0)이다. 그래서 체크형은 NO_RECORD, 저장값 0.00이다. */
@Component
public class NoActionCounter implements WeeklyActionCounter {

	@Override
	public ActionCounts counts(long weeklyGoalId) {
		return new ActionCounts(0, 0);
	}
}
