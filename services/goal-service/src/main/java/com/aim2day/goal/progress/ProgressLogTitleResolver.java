package com.aim2day.goal.progress;

import com.aim2day.goal.goals.yearly.YearlyGoal;
import com.aim2day.goal.goals.yearly.YearlyGoalRepository;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 진행 기록의 target_type·target_id(폴리모픽 참조)를 목표 제목으로 바꾼다.
 * 기록 수만큼 목표를 하나씩 읽지 않고 타입별로 id를 모아 한 번에 조회한다.
 * 삭제된 목표의 기록도 제목과 함께 보여 주므로 deleted_at 조건 없이 읽는다.
 * 월간·주간 목표 테이블은 단위 3에서 만들어지므로 이번 단위는 YEARLY_GOAL만 처리한다.
 * 단위 3은 아래 switch에 두 분기를 더하면 된다. 해석하지 못한 대상은 제목이 null이다.
 */
@Component
public class ProgressLogTitleResolver {

	private final YearlyGoalRepository yearlyGoals;

	public ProgressLogTitleResolver(YearlyGoalRepository yearlyGoals) {
		this.yearlyGoals = yearlyGoals;
	}

	/** 타입별 중첩 맵이라 서로 다른 단계의 목표가 같은 id를 가져도 섞이지 않는다. */
	public Map<ProgressTargetType, Map<Long, String>> resolve(Collection<GoalProgressLog> logs) {
		Map<ProgressTargetType, Set<Long>> idsByType = new HashMap<>();
		for (GoalProgressLog log : logs) {
			idsByType.computeIfAbsent(log.getTargetType(), k -> new HashSet<>()).add(log.getTargetId());
		}
		Map<ProgressTargetType, Map<Long, String>> result = new HashMap<>();
		idsByType.forEach((type, ids) -> result.put(type, titlesOf(type, ids)));
		return result;
	}

	private Map<Long, String> titlesOf(ProgressTargetType type, Set<Long> ids) {
		Map<Long, String> titles = new HashMap<>();
		switch (type) {
			case YEARLY_GOAL -> {
				List<YearlyGoal> goals = yearlyGoals.findAllByIdIn(ids);
				goals.forEach(g -> titles.put(g.getId(), g.getTitle()));
			}
			case MONTHLY_GOAL, WEEKLY_GOAL -> {
				// 단위 3에서 월간·주간 목표 저장소로 채운다.
			}
		}
		return titles;
	}
}
