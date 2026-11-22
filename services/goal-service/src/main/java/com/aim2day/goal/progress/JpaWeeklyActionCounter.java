package com.aim2day.goal.progress;

import com.aim2day.goal.actions.ActionRepository;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 체크형 주간 목표의 실제 집계. 완료 행동(삭제 포함) / (미삭제 행동 + 삭제된 완료 행동).
 * 삭제한 완료 행동은 완료 기록이라 분자·분모에 그대로 남고, 삭제한 미완료 행동은 분모에서 빠진다(ERD 8번, API 명세 §4.9).
 * 집계 쿼리는 영속성 컨텍스트가 아니라 DB를 읽는다. 진행률 엔진은 호출 전에 주간 목표 행을 잠그고 flush하므로
 * 같은 트랜잭션의 완료·삭제 변경이 모두 반영된다.
 */
@Component
public class JpaWeeklyActionCounter implements WeeklyActionCounter {

	private final ActionRepository actions;

	public JpaWeeklyActionCounter(ActionRepository actions) {
		this.actions = actions;
	}

	/** 주간 목표 하나의 완료 수와 분모를 DB에서 센다. */
	@Override
	public ActionCounts counts(long weeklyGoalId) {
		return new ActionCounts((int) actions.countCompleted(weeklyGoalId),
				(int) actions.countDenominator(weeklyGoalId));
	}

	/** 주간 목표 여러 개를 한 쿼리로 센다. 행동이 없는 주간 목표는 (0, 0)으로 채워 모든 id가 결과에 들어 있게 한다. */
	@Override
	public Map<Long, ActionCounts> countsFor(Collection<Long> weeklyGoalIds) {
		Map<Long, ActionCounts> result = new HashMap<>();
		if (weeklyGoalIds.isEmpty()) {
			return result;
		}
		for (Long id : weeklyGoalIds) {
			result.put(id, new ActionCounts(0, 0));
		}
		for (Object[] row : actions.countByWeeklyGoals(weeklyGoalIds)) {
			result.put(((Number) row[0]).longValue(),
					new ActionCounts(((Number) row[1]).intValue(), ((Number) row[2]).intValue()));
		}
		return result;
	}
}
