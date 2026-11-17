package com.aim2day.goal.actions;

import com.aim2day.goal.actions.dto.ActionListResponse;
import com.aim2day.goal.actions.dto.ActionResponse;
import com.aim2day.goal.settings.SettingsService;
import com.aim2day.goal.settings.UserTime;
import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 날짜별 행동 목록(API 명세 §4.9 GET). 목록·요약·지난 미완료를 한 응답에 담으므로 읽기 전용
 * REPEATABLE READ 트랜잭션 하나로 같은 스냅샷에서 읽는다(요약 수와 항목이 서로 어긋나지 않게).
 */
@Service
public class ActionListService {

	private final ActionRepository actions;
	private final ActionAssembler assembler;
	private final SettingsService settingsService;
	private final TransactionTemplate readTx;

	public ActionListService(ActionRepository actions, ActionAssembler assembler, SettingsService settingsService,
			PlatformTransactionManager txManager) {
		this.actions = actions;
		this.assembler = assembler;
		this.settingsService = settingsService;
		this.readTx = new TransactionTemplate(txManager);
		this.readTx.setReadOnly(true);
		this.readTx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
	}

	public ActionListResponse list(long userId, LocalDate date, ActionSort sort) {
		// 오늘은 트랜잭션 밖에서 먼저 읽는다(SettingsService는 자기 트랜잭션을 쓴다).
		UserTime time = settingsService.userTime(userId);
		LocalDate target = date != null ? date : time.today();
		ActionSort effectiveSort = sort != null ? sort : ActionSort.IMPORTANCE_URGENCY;
		return readTx.execute(status -> {
			List<ActionResponse> items = ActionSorter.sort(
					assembler.responses(actions.findListedByDate(userId, target), time.today()), effectiveSort);

			// 요약은 그날 목록(삭제·숨김 제외)의 GOAL·MAINTENANCE별 전체·완료 수다. 지난 미완료는 포함하지 않는다.
			ActionListResponse.Counts goal = counts(items, ActionType.GOAL);
			ActionListResponse.Counts maintenance = counts(items, ActionType.MAINTENANCE);

			// 사분면 분류는 단위 5에서 구현한다. 그 전에는 모든 행동이 미분류이고 확정한 변경이 없다.
			ActionListResponse.Classification classification =
					new ActionListResponse.Classification(items.size(), 0, 0, false);

			// 지난 미완료는 오늘 목록을 볼 때만 채운다. 서버가 수행일을 자동으로 바꾸지 않는다(결정 10).
			ActionListResponse.PastIncomplete past = null;
			if (target.equals(time.today())) {
				List<ActionResponse> pastItems = assembler.responses(
						actions.findPastIncomplete(userId, time.today()), time.today());
				past = new ActionListResponse.PastIncomplete(pastItems.size(), pastItems);
			}
			return new ActionListResponse(target, effectiveSort, new ActionListResponse.Summary(goal, maintenance),
					classification, items, past);
		});
	}

	private static ActionListResponse.Counts counts(List<ActionResponse> items, ActionType type) {
		int total = 0;
		int completed = 0;
		for (ActionResponse item : items) {
			if (item.type() == type) {
				total++;
				if (item.isCompleted()) {
					completed++;
				}
			}
		}
		return new ActionListResponse.Counts(total, completed);
	}
}
