package com.aim2day.goal.goals;

import com.aim2day.goal.goals.monthly.MonthlyGoal;
import com.aim2day.goal.goals.monthly.MonthlyGoalRepository;
import com.aim2day.goal.goals.monthly.dto.MonthlyGoalDtos.MonthlyGoalDetailResponse;
import com.aim2day.goal.goals.monthly.dto.MonthlyGoalDtos.MonthlyGoalResponse;
import com.aim2day.goal.goals.weekly.WeeklyGoal;
import com.aim2day.goal.goals.weekly.WeeklyGoalRepository;
import com.aim2day.goal.goals.weekly.dto.WeeklyGoalDtos.WeeklyGoalResponse;
import com.aim2day.goal.goals.yearly.YearlyGoal;
import com.aim2day.goal.goals.yearly.YearlyGoalRepository;
import com.aim2day.goal.progress.WeeklyActionCounter;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * 월간·주간 응답과 연간 상세 트리를 만든다. 응답에는 상위 목표의 제목·색·상태가 필요한데, 이를 서비스마다
 * 따로 읽으면 연간 서비스가 월간 서비스를, 월간 서비스가 연간 서비스를 부르는 순환이 생긴다.
 * 그래서 저장소만 쓰는 이 도우미에 모았다. 상위 목표는 id 묶음으로 한 번에 읽는다(N+1 방지).
 * 호출자는 읽기 전용 트랜잭션 안에서 부른다. 한 응답의 모든 테이블 읽기가 같은 스냅샷이어야 하기 때문이다.
 */
@Component
public class GoalAssembler {

	private final YearlyGoalRepository yearlies;
	private final MonthlyGoalRepository monthlies;
	private final WeeklyGoalRepository weeklies;
	private final WeeklyActionCounter actionCounter;

	public GoalAssembler(YearlyGoalRepository yearlies, MonthlyGoalRepository monthlies,
			WeeklyGoalRepository weeklies, WeeklyActionCounter actionCounter) {
		this.yearlies = yearlies;
		this.monthlies = monthlies;
		this.weeklies = weeklies;
		this.actionCounter = actionCounter;
	}

	/** 월간 목표들을 응답으로 바꾼다. 상위 연간 목표와 하위 주간 목표 수를 묶음 쿼리로 한 번씩만 읽는다. */
	public List<MonthlyGoalResponse> monthlyResponses(List<MonthlyGoal> goals, LocalDate today) {
		if (goals.isEmpty()) {
			return List.of();
		}
		Map<Long, YearlyGoal> yearlyById = yearlies
				.findAllByIdIn(goals.stream().map(MonthlyGoal::getYearlyGoalId).collect(Collectors.toSet())).stream()
				.collect(Collectors.toMap(YearlyGoal::getId, Function.identity()));
		Map<Long, Integer> counts = weeklyCounts(goals.stream().map(MonthlyGoal::getId).toList());
		return goals.stream()
				.map(m -> MonthlyGoalResponse.of(m, yearlyById.get(m.getYearlyGoalId()),
						counts.getOrDefault(m.getId(), 0), today))
				.toList();
	}

	/** 주간 목표들을 응답으로 바꾼다. 상위 월간·연간 목표와 행동 집계를 묶음 쿼리로 읽어 경로 정보와 진행률을 채운다. */
	public List<WeeklyGoalResponse> weeklyResponses(List<WeeklyGoal> goals, LocalDate today) {
		if (goals.isEmpty()) {
			return List.of();
		}
		Map<Long, MonthlyGoal> monthlyById = monthlies
				.findAllByIdIn(goals.stream().map(WeeklyGoal::getMonthlyGoalId).collect(Collectors.toSet())).stream()
				.collect(Collectors.toMap(MonthlyGoal::getId, Function.identity()));
		Set<Long> yearlyIds = new HashSet<>();
		monthlyById.values().forEach(m -> yearlyIds.add(m.getYearlyGoalId()));
		Map<Long, YearlyGoal> yearlyById = yearlies.findAllByIdIn(yearlyIds).stream()
				.collect(Collectors.toMap(YearlyGoal::getId, Function.identity()));
		// 행동 수는 주간마다 부르지 않고 한 쿼리로 묶어 센다(주간 N개에 SQL 2N회가 나가는 것을 막는다).
		Map<Long, com.aim2day.goal.progress.ActionCounts> countsById = actionCounter
				.countsFor(goals.stream().map(WeeklyGoal::getId).toList());
		return goals.stream().map(w -> {
			MonthlyGoal m = monthlyById.get(w.getMonthlyGoalId());
			return WeeklyGoalResponse.of(w, m, yearlyById.get(m.getYearlyGoalId()),
					countsById.get(w.getId()), today);
		}).toList();
	}

	/** 월간 목표 목록에 각 월간의 하위 주간 목표를 붙인 트리. 월간은 이미 원하는 순서로 정렬되어 들어온다. */
	public List<MonthlyGoalDetailResponse> monthlyTrees(List<MonthlyGoal> goals, LocalDate today) {
		List<MonthlyGoalResponse> monthlyViews = monthlyResponses(goals, today);
		if (goals.isEmpty()) {
			return List.of();
		}
		List<WeeklyGoal> children = weeklies
				.findActiveByMonthlyGoals(goals.stream().map(MonthlyGoal::getId).toList());
		Map<Long, List<WeeklyGoalResponse>> byMonthly = new HashMap<>();
		List<WeeklyGoalResponse> childViews = weeklyResponses(children, today);
		for (int i = 0; i < children.size(); i++) {
			byMonthly.computeIfAbsent(children.get(i).getMonthlyGoalId(), k -> new java.util.ArrayList<>())
					.add(childViews.get(i));
		}
		return monthlyViews.stream()
				.map(m -> MonthlyGoalDetailResponse.of(m, byMonthly.getOrDefault(m.id(), List.of())))
				.toList();
	}

	private Map<Long, Integer> weeklyCounts(List<Long> monthlyIds) {
		Map<Long, Integer> counts = new HashMap<>();
		for (Object[] row : weeklies.countActiveByMonthlyGoals(monthlyIds)) {
			counts.put(((Number) row[0]).longValue(), ((Number) row[1]).intValue());
		}
		return counts;
	}
}
