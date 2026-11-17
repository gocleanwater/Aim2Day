package com.aim2day.goal.progress;

import com.aim2day.goal.goals.monthly.MonthlyGoal;
import com.aim2day.goal.goals.monthly.MonthlyGoalRepository;
import com.aim2day.goal.goals.weekly.WeeklyGoal;
import com.aim2day.goal.goals.weekly.WeeklyGoalRepository;
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
 * 해석하지 못한 대상(이미 영구 삭제된 목표 등)은 제목이 null이다.
 */
@Component
public class ProgressLogTitleResolver {

	private final YearlyGoalRepository yearlyGoals;
	private final MonthlyGoalRepository monthlyGoals;
	private final WeeklyGoalRepository weeklyGoals;

	public ProgressLogTitleResolver(YearlyGoalRepository yearlyGoals, MonthlyGoalRepository monthlyGoals,
			WeeklyGoalRepository weeklyGoals) {
		this.yearlyGoals = yearlyGoals;
		this.monthlyGoals = monthlyGoals;
		this.weeklyGoals = weeklyGoals;
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
			case MONTHLY_GOAL -> {
				List<MonthlyGoal> goals = monthlyGoals.findAllByIdIn(ids);
				goals.forEach(g -> titles.put(g.getId(), g.getTitle()));
			}
			case WEEKLY_GOAL -> {
				List<WeeklyGoal> goals = weeklyGoals.findAllByIdIn(ids);
				goals.forEach(g -> titles.put(g.getId(), g.getTitle()));
			}
		}
		return titles;
	}
}
