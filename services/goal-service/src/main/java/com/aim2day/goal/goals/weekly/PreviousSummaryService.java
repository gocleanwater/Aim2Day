package com.aim2day.goal.goals.weekly;

import com.aim2day.goal.actions.Action;
import com.aim2day.goal.actions.ActionRepository;
import com.aim2day.goal.common.tx.Tx;
import com.aim2day.goal.goals.GoalAssembler;
import com.aim2day.goal.goals.GoalRef;
import com.aim2day.goal.goals.weekly.dto.WeeklyGoalDtos.PreviousSummaryResponse;
import com.aim2day.goal.goals.weekly.dto.WeeklyGoalDtos.WeeklyGoalResponse;
import com.aim2day.goal.settings.SettingsService;
import com.aim2day.goal.settings.UserTime;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 주초 계획 화면의 직전 주 요약(PLAN-003). 직전 주는 현재 주 시작일 7일 전부터 6일 뒤까지다.
 * 그 기간과 하루라도 겹치는 숨김 아닌 주간 목표를 담고, 각 주간 목표의 연결된 미삭제 행동을 완료·남은 일로 나눈다.
 * 목표·행동·진행률을 여러 테이블에서 읽으므로 읽기 전용 REPEATABLE READ 트랜잭션 하나로 한 스냅샷에서 읽는다.
 */
@Service
public class PreviousSummaryService {

	private final WeeklyGoalRepository weeklies;
	private final ActionRepository actions;
	private final GoalAssembler assembler;
	private final SettingsService settingsService;
	private final TransactionTemplate readTx;

	public PreviousSummaryService(WeeklyGoalRepository weeklies, ActionRepository actions, GoalAssembler assembler,
			SettingsService settingsService, PlatformTransactionManager txManager) {
		this.weeklies = weeklies;
		this.actions = actions;
		this.assembler = assembler;
		this.settingsService = settingsService;
		this.readTx = Tx.readOnly(txManager);
	}

	/**
	 * 직전 주 요약 조회(GET /api/goals/weekly/previous-summary).
	 * 처리: 계정 설정의 주 시작 요일 기준 현재 주 바로 앞 7일을 구하고, 그 기간과 겹치는 내게 보이는 주간 목표와 그 행동을 한 스냅샷으로 읽는다.
	 * 반환: 직전 주 기간과 주간 목표별 요약. 해당 주간 목표가 없으면 빈 목록.
	 */
	public PreviousSummaryResponse summary(long userId) {
		UserTime time = settingsService.userTime(userId);
		LocalDate start = time.currentWeek().startDate().minusDays(7);
		LocalDate end = time.currentWeek().startDate().minusDays(1);
		return readTx.execute(status -> {
			List<WeeklyGoal> goals = weeklies.findVisibleOverlapping(userId, start, end);
			if (goals.isEmpty()) {
				return new PreviousSummaryResponse(start, end, List.of());
			}
			List<WeeklyGoalResponse> views = assembler.weeklyResponses(goals, time.today());
			// 수행일, id 오름차순으로 읽은 행동을 주간 목표별로 나눈다.
			Map<Long, List<Action>> byWeekly = new HashMap<>();
			for (Action a : actions.findActiveByWeeklyGoals(goals.stream().map(WeeklyGoal::getId).toList())) {
				byWeekly.computeIfAbsent(a.getWeeklyGoalId(), k -> new ArrayList<>()).add(a);
			}
			List<PreviousSummaryResponse.WeeklyEntry> entries = new ArrayList<>();
			for (WeeklyGoalResponse w : views) {
				List<PreviousSummaryResponse.ActionBrief> completed = new ArrayList<>();
				List<PreviousSummaryResponse.ActionBrief> remaining = new ArrayList<>();
				for (Action a : byWeekly.getOrDefault(w.id(), List.of())) {
					PreviousSummaryResponse.ActionBrief brief =
							new PreviousSummaryResponse.ActionBrief(a.getId(), a.getTitle(), a.getPerformedDate());
					if (a.isCompleted()) {
						completed.add(brief);
					} else {
						remaining.add(brief);
					}
				}
				entries.add(new PreviousSummaryResponse.WeeklyEntry(w.id(), w.title(),
						new PreviousSummaryResponse.Path(w.path().yearlyGoal(),
								new GoalRef(w.path().monthlyGoal().id(), w.path().monthlyGoal().title())),
						w.progress(), completed, remaining));
			}
			return new PreviousSummaryResponse(start, end, entries);
		});
	}
}
