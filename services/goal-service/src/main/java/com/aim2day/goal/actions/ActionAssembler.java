package com.aim2day.goal.actions;

import com.aim2day.goal.actions.dto.ActionDtos.ActionResponse;
import com.aim2day.goal.actions.dto.ActionDtos.ActionResponse.Placement;
import com.aim2day.goal.common.time.UtcTime;
import com.aim2day.goal.goals.GoalPath;
import com.aim2day.goal.goals.GoalRef;
import com.aim2day.goal.goals.MonthlyRef;
import com.aim2day.goal.goals.monthly.MonthlyGoal;
import com.aim2day.goal.goals.monthly.MonthlyGoalRepository;
import com.aim2day.goal.goals.weekly.WeeklyGoal;
import com.aim2day.goal.goals.weekly.WeeklyGoalRepository;
import com.aim2day.goal.goals.yearly.YearlyGoal;
import com.aim2day.goal.goals.yearly.YearlyGoalRepository;
import com.aim2day.goal.quadrant.PlacementViews;
import com.aim2day.goal.quadrant.QuadrantPlacement;
import com.aim2day.goal.quadrant.QuadrantPlacementRepository;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * 행동 객체를 만든다. 응답에는 연결 경로(주간 -> 월간 -> 연간)의 제목·색·상태가 필요한데, 행동마다 읽으면 N+1이
 * 되므로 id 묶음으로 한 번에 읽는다. 경로가 삭제된 완료 행동도 경로 이름을 보여 주므로 삭제 여부와 무관하게 읽는다.
 * 호출자는 트랜잭션 안에서 부른다(조회는 읽기 전용 REPEATABLE READ, 쓰기는 자신의 쓰기 트랜잭션).
 */
@Component
public class ActionAssembler {

	private final WeeklyGoalRepository weeklies;
	private final MonthlyGoalRepository monthlies;
	private final YearlyGoalRepository yearlies;
	private final QuadrantPlacementRepository placements;

	public ActionAssembler(WeeklyGoalRepository weeklies, MonthlyGoalRepository monthlies,
			YearlyGoalRepository yearlies, QuadrantPlacementRepository placements) {
		this.placements = placements;
		this.weeklies = weeklies;
		this.monthlies = monthlies;
		this.yearlies = yearlies;
	}

	public List<ActionResponse> responses(List<Action> actions, LocalDate today) {
		if (actions.isEmpty()) {
			return List.of();
		}
		Chains chains = loadChains(actions);
		Map<Long, WeeklyGoal> weeklyById = chains.weekly();
		Map<Long, MonthlyGoal> monthlyById = chains.monthly();
		Map<Long, YearlyGoal> yearlyById = chains.yearly();
		// 좌표는 행동의 수행일에 해당하는 행이다(한 쿼리). 수행일이 오늘이 아니면 확정 좌표만 쓴다(결정 18).
		Map<Long, QuadrantPlacement> placementByAction = placements
				.findForActions(actions.stream().map(Action::getId).toList()).stream()
				.collect(Collectors.toMap(QuadrantPlacement::getActionId, Function.identity()));
		return actions.stream().map(a -> {
			Placement placement = PlacementViews.of(placementByAction.get(a.getId()),
					a.getPerformedDate().equals(today));
			if (a.getWeeklyGoalId() == null) {
				return of(a, null, null, null, today, placement);
			}
			WeeklyGoal w = weeklyById.get(a.getWeeklyGoalId());
			MonthlyGoal m = monthlyById.get(w.getMonthlyGoalId());
			return of(a, w, m, yearlyById.get(m.getYearlyGoalId()), today, placement);
		}).toList();
	}

	/** 행동들이 가리키는 주간 -> 월간 -> 연간 목표를 삭제 여부와 무관하게 id 묶음으로 한 번씩만 읽는다(N+1 방지). */
	private Chains loadChains(List<Action> actions) {
		Set<Long> weeklyIds = new HashSet<>();
		actions.forEach(a -> {
			if (a.getWeeklyGoalId() != null) {
				weeklyIds.add(a.getWeeklyGoalId());
			}
		});
		Map<Long, WeeklyGoal> weeklyById = weeklyIds.isEmpty() ? Map.of()
				: weeklies.findAllByIdIn(weeklyIds).stream()
						.collect(Collectors.toMap(WeeklyGoal::getId, Function.identity()));
		Map<Long, MonthlyGoal> monthlyById = weeklyById.isEmpty() ? Map.of()
				: monthlies.findAllByIdIn(weeklyById.values().stream().map(WeeklyGoal::getMonthlyGoalId)
						.collect(Collectors.toSet())).stream()
						.collect(Collectors.toMap(MonthlyGoal::getId, Function.identity()));
		Map<Long, YearlyGoal> yearlyById = monthlyById.isEmpty() ? Map.of()
				: yearlies.findAllByIdIn(monthlyById.values().stream().map(MonthlyGoal::getYearlyGoalId)
						.collect(Collectors.toSet())).stream()
						.collect(Collectors.toMap(YearlyGoal::getId, Function.identity()));
		return new Chains(weeklyById, monthlyById, yearlyById);
	}

	private record Chains(Map<Long, WeeklyGoal> weekly, Map<Long, MonthlyGoal> monthly, Map<Long, YearlyGoal> yearly) {
	}

	/**
	 * 행동별 goalPath(연결된 목표의 현재 이름)를 행동 id -> 경로로 돌려준다. 리포트(§4.12)가 쓴다. MAINTENANCE는 맵에 없다.
	 * 좌표 조회 없이 경로만 필요하므로 responses()와 같은 조립 코드(loadChains, pathView)를 재사용한다.
	 */
	public Map<Long, ActionResponse.GoalPathView> goalPaths(List<Action> actions) {
		if (actions.isEmpty()) {
			return Map.of();
		}
		Chains chains = loadChains(actions);
		Map<Long, ActionResponse.GoalPathView> result = new java.util.HashMap<>();
		for (Action a : actions) {
			if (a.getWeeklyGoalId() == null) {
				continue;
			}
			WeeklyGoal w = chains.weekly().get(a.getWeeklyGoalId());
			MonthlyGoal m = chains.monthly().get(w.getMonthlyGoalId());
			result.put(a.getId(), pathView(w, m, chains.yearly().get(m.getYearlyGoalId())));
		}
		return result;
	}

	private static ActionResponse.GoalPathView pathView(WeeklyGoal w, MonthlyGoal m, YearlyGoal y) {
		return new ActionResponse.GoalPathView(new GoalRef(y.getId(), y.getTitle()),
				new MonthlyRef(m.getId(), m.getTitle(), YearMonth.from(m.getStartDate()).toString()),
				new GoalRef(w.getId(), w.getTitle()));
	}

	static ActionResponse of(Action a, WeeklyGoal w, MonthlyGoal m, YearlyGoal y, LocalDate today,
			Placement placement) {
		// daysUntilDue는 조회 날짜가 아니라 오늘 기준이다(§4.9). 기한이 없으면 null이다.
		Integer daysUntilDue = a.getDueDate() == null ? null
				: (int) ChronoUnit.DAYS.between(today, a.getDueDate());
		boolean goal = w != null;
		ActionResponse.GoalPathView path = goal ? pathView(w, m, y) : null;
		return new ActionResponse(a.getId(), a.getType(), a.getTitle(), a.getPerformedDate(), a.getDueDate(),
				daysUntilDue, a.isCompleted(), UtcTime.toInstant(a.getCompletedAt()),
				goal ? a.getContributionValue() : null, a.getResultSummary(), path,
				goal ? GoalPath.pathStatus(w.getStatus(), m.getStatus(), y.getStatus()) : null,
				goal ? GoalPath.displayColor(w.getColor(), m.getColor(), y.getColor())
						: ActionResponse.MAINTENANCE_COLOR,
				placement, a.getVersion());
	}
}
