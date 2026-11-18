package com.aim2day.goal.onboarding;

import com.aim2day.goal.actions.ActionService;
import com.aim2day.goal.actions.ActionType;
import com.aim2day.goal.common.exception.FieldErrors;
import com.aim2day.goal.common.exception.ValidationReason;
import com.aim2day.goal.common.lock.UserRowLock;
import com.aim2day.goal.common.tx.Tx;
import com.aim2day.goal.goals.monthly.MonthlyGoalService;
import com.aim2day.goal.goals.weekly.WeeklyGoalService;
import com.aim2day.goal.goals.yearly.YearlyGoalService;
import com.aim2day.goal.onboarding.dto.OnboardingDtos.ActionItem;
import com.aim2day.goal.onboarding.dto.OnboardingDtos.CompleteRequest;
import com.aim2day.goal.onboarding.dto.OnboardingDtos.CompleteRequest.MonthlyItem;
import com.aim2day.goal.onboarding.dto.OnboardingDtos.CompleteRequest.WeeklyItem;
import com.aim2day.goal.onboarding.dto.OnboardingDtos.CompleteRequest.YearlyItem;
import com.aim2day.goal.onboarding.dto.OnboardingDtos.CompleteResponse;
import com.aim2day.goal.settings.SettingsService;
import com.aim2day.goal.settings.UserTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 온보딩 일괄 저장(API 명세 §4.2 complete). 연간 -> 월간 -> 주간 -> 행동을 한 트랜잭션으로 만들고 임시 저장을 지운다.
 * 어느 단계든 예외가 나면 트랜잭션 전체가 롤백돼 아무것도 만들어지지 않고 임시 저장도 그대로 남는다.
 *
 * 검증·생성 규칙은 개별 생성 서비스(YearlyGoalService·MonthlyGoalService·WeeklyGoalService·ActionService)의
 * validateNew / insertInTx를 그대로 부른다. 규칙을 여기에 복사하면 개별 API와 규칙이 어긋나므로 복사하지 않는다.
 *
 * 순서
 * 1. 구조·필드 검증(400)을 DB를 건드리기 전에 모두 모아서 한 번에 낸다.
 * 2. 트랜잭션의 첫 쿼리로 users 행을 잠근다. 연간 한도(3개) 확인이 같은 사용자의 다른 요청(다른 인스턴스의 완료·연간 생성)과
 *    직렬화되고, 진행 기록 INSERT의 FK 확인과 교착하지 않는다(잠금 순서 users -> 주간 -> 월간).
 * 3. 연간 생성이 한도(기존 활성 + 새 연간 수 > 3)를 넘으면 409, 기존 id 부모는 insertInTx가 소유·가시성·기간을 확인해 422다.
 * 4. 연간 -> 월간 -> 주간 -> 행동 순서로 만든다. 부모가 방금 만든 항목이면 tempId를 실제 id로 바꿔서 넘긴다.
 *    진행률은 개별 생성과 같은 ProgressEngine 호출(insertInTx 안)로 계산된다.
 */
@Service
public class OnboardingCompleteService {

	private final YearlyGoalService yearlyService;
	private final MonthlyGoalService monthlyService;
	private final WeeklyGoalService weeklyService;
	private final ActionService actionService;
	private final OnboardingDraftRepository draftRepository;
	private final SettingsService settingsService;
	private final UserRowLock userRowLock;
	private final TransactionTemplate tx;

	public OnboardingCompleteService(YearlyGoalService yearlyService, MonthlyGoalService monthlyService,
			WeeklyGoalService weeklyService, ActionService actionService, OnboardingDraftRepository draftRepository,
			SettingsService settingsService, UserRowLock userRowLock, PlatformTransactionManager txManager) {
		this.yearlyService = yearlyService;
		this.monthlyService = monthlyService;
		this.weeklyService = weeklyService;
		this.actionService = actionService;
		this.draftRepository = draftRepository;
		this.settingsService = settingsService;
		this.userRowLock = userRowLock;
		// 격리 수준을 지정하지 않는다(MySQL 기본 REPEATABLE READ). users 행 잠금이 첫 쿼리라 그 뒤 읽기는 앞선 요청의 커밋을 본다.
		this.tx = Tx.write(txManager);
	}

	public CompleteResponse complete(long userId, CompleteRequest req) {
		// "올해·이번 달·이번 주·오늘"은 계정 설정 기준이다. 트랜잭션 밖에서 먼저 읽는다.
		UserTime time = settingsService.userTime(userId);
		List<YearlyItem> yearlyItems = orEmpty(req.yearlyGoals());
		List<MonthlyItem> monthlyItems = orEmpty(req.monthlyGoals());
		List<WeeklyItem> weeklyItems = orEmpty(req.weeklyGoals());
		List<ActionItem> actionItems = orEmpty(req.actions());

		// ---- 1. 구조·필드 검증(400). 항목마다 필드 경로(yearlyGoals[0].title)로 오류를 모아 한 번에 낸다.
		FieldErrors errors = new FieldErrors();
		if (yearlyItems.isEmpty() && monthlyItems.isEmpty() && weeklyItems.isEmpty() && actionItems.isEmpty()) {
			// 만들 것이 없는 요청은 임시 저장만 지우고 끝나 온보딩이 끝난 것처럼 보이므로 받지 않는다(가정).
			errors.add("yearlyGoals", ValidationReason.REQUIRED);
		}

		Set<String> yearlyTemps = new HashSet<>();
		List<YearlyGoalService.NewGoal> yearlyDrafts = new ArrayList<>();
		for (int i = 0; i < yearlyItems.size(); i++) {
			YearlyItem item = yearlyItems.get(i);
			if (item == null) {
				errors.add("yearlyGoals[" + i + "]", ValidationReason.REQUIRED);
				yearlyDrafts.add(null);
				continue;
			}
			FieldErrors scope = errors.scoped("yearlyGoals[" + i + "].");
			checkTempId(item.tempId(), yearlyTemps, scope);
			yearlyDrafts.add(YearlyGoalService.validateNew(item.title(), item.description(), item.color(), scope));
		}

		Set<String> monthlyTemps = new HashSet<>();
		List<MonthlyGoalService.NewGoal> monthlyDrafts = new ArrayList<>();
		for (int i = 0; i < monthlyItems.size(); i++) {
			MonthlyItem item = monthlyItems.get(i);
			if (item == null) {
				errors.add("monthlyGoals[" + i + "]", ValidationReason.REQUIRED);
				monthlyDrafts.add(null);
				continue;
			}
			FieldErrors scope = errors.scoped("monthlyGoals[" + i + "].");
			checkTempId(item.tempId(), monthlyTemps, scope);
			requireOneParent(scope, "yearlyGoalTempId", item.yearlyGoalTempId(), "yearlyGoalId", item.yearlyGoalId(),
					yearlyTemps);
			monthlyDrafts.add(MonthlyGoalService.validateNew(item.title(), item.description(), item.color(), scope));
		}

		Set<String> weeklyTemps = new HashSet<>();
		List<WeeklyGoalService.NewGoal> weeklyDrafts = new ArrayList<>();
		for (int i = 0; i < weeklyItems.size(); i++) {
			WeeklyItem item = weeklyItems.get(i);
			if (item == null) {
				errors.add("weeklyGoals[" + i + "]", ValidationReason.REQUIRED);
				weeklyDrafts.add(null);
				continue;
			}
			FieldErrors scope = errors.scoped("weeklyGoals[" + i + "].");
			checkTempId(item.tempId(), weeklyTemps, scope);
			requireOneParent(scope, "monthlyGoalTempId", item.monthlyGoalTempId(), "monthlyGoalId",
					item.monthlyGoalId(), monthlyTemps);
			weeklyDrafts.add(WeeklyGoalService.validateNew(item.title(), item.description(), item.color(),
					item.measurementType(), item.weight(), item.targetValue(), scope));
		}

		List<ActionService.NewAction> actionDrafts = new ArrayList<>();
		for (int i = 0; i < actionItems.size(); i++) {
			ActionItem item = actionItems.get(i);
			if (item == null) {
				errors.add("actions[" + i + "]", ValidationReason.REQUIRED);
				actionDrafts.add(null);
				continue;
			}
			FieldErrors scope = errors.scoped("actions[" + i + "].");
			List<String> presentLinks = new ArrayList<>();
			if (item.monthlyGoalTempIdPresent()) {
				presentLinks.add("monthlyGoalTempId");
			}
			if (item.monthlyGoalIdPresent()) {
				presentLinks.add("monthlyGoalId");
			}
			if (item.weeklyGoalTempIdPresent()) {
				presentLinks.add("weeklyGoalTempId");
			}
			if (item.weeklyGoalIdPresent()) {
				presentLinks.add("weeklyGoalId");
			}
			boolean hasLink = item.monthlyGoalTempId() != null || item.monthlyGoalId() != null
					|| item.weeklyGoalTempId() != null || item.weeklyGoalId() != null;
			// 필드 규칙(제목·기여량·MAINTENANCE의 연결 금지·GOAL의 연결 필수)은 개별 행동 생성과 같은 메서드가 검사한다.
			// 연결 id는 아래 트랜잭션에서 tempId를 풀어 채우므로 여기서는 비워 둔다.
			actionDrafts.add(ActionService.validateNew(item.type(), item.title(), item.contributionValue(),
					item.contributionValuePresent(), presentLinks, hasLink, "weeklyGoalId", null, null,
					item.performedDate(), item.dueDate(), time, scope));
			if (item.type() != ActionType.MAINTENANCE) {
				// 월간·주간 각각 tempId와 id 중 하나만 쓸 수 있다. 행동은 둘 중 하나만 있어도 되므로 "없음"은 오류가 아니다.
				checkOptionalParent(scope, "monthlyGoalTempId", item.monthlyGoalTempId(), item.monthlyGoalId(),
						monthlyTemps);
				checkOptionalParent(scope, "weeklyGoalTempId", item.weeklyGoalTempId(), item.weeklyGoalId(),
						weeklyTemps);
			}
		}
		errors.throwIfAny();

		// ---- 2~5. 한 트랜잭션
		int year = time.today().getYear();
		return tx.execute(status -> {
			// 첫 쿼리는 users 행 잠금이다(클래스 설명).
			userRowLock.lock(userId);

			// 연간: 기존 활성 + 새 연간 수가 3을 넘으면 insertInTx가 GOAL_LIMIT_EXCEEDED(409)를 던진다.
			// 앞서 넣은 새 목표도 개수에 들어가므로 "기존 + 새 > 3"과 같은 결과다.
			Map<String, Long> yearlyIds = new LinkedHashMap<>();
			for (int i = 0; i < yearlyItems.size(); i++) {
				yearlyIds.put(yearlyItems.get(i).tempId(),
						yearlyService.insertInTx(userId, year, yearlyDrafts.get(i)).getId());
			}

			Map<String, Long> monthlyIds = new LinkedHashMap<>();
			for (int i = 0; i < monthlyItems.size(); i++) {
				MonthlyItem item = monthlyItems.get(i);
				// 부모가 기존 id면 insertInTx가 소유자·삭제 여부(422 GOAL_PARENT_UNAVAILABLE)와 기간(422 ...MISMATCH)을 확인한다.
				long parentId = item.yearlyGoalTempId() != null ? yearlyIds.get(item.yearlyGoalTempId())
						: item.yearlyGoalId();
				monthlyIds.put(item.tempId(), monthlyService.insertInTx(userId, parentId, time, monthlyDrafts.get(i)));
			}

			Map<String, Long> weeklyIds = new LinkedHashMap<>();
			for (int i = 0; i < weeklyItems.size(); i++) {
				WeeklyItem item = weeklyItems.get(i);
				long parentId = item.monthlyGoalTempId() != null ? monthlyIds.get(item.monthlyGoalTempId())
						: item.monthlyGoalId();
				weeklyIds.put(item.tempId(), weeklyService.insertInTx(userId, parentId, time, weeklyDrafts.get(i)));
			}

			// 행동은 요청 순서대로 만들고 id도 그 순서로 돌려준다.
			List<Long> actionIds = new ArrayList<>();
			for (int i = 0; i < actionItems.size(); i++) {
				ActionItem item = actionItems.get(i);
				ActionService.NewAction draft = actionDrafts.get(i);
				Long monthlyId = item.monthlyGoalTempId() != null ? monthlyIds.get(item.monthlyGoalTempId())
						: item.monthlyGoalId();
				Long weeklyId = item.weeklyGoalTempId() != null ? weeklyIds.get(item.weeklyGoalTempId())
						: item.weeklyGoalId();
				ActionService.NewAction resolved = new ActionService.NewAction(draft.type(), monthlyId, weeklyId,
						draft.title(), draft.performedDate(), draft.dueDate(), draft.contribution());
				ActionService.requireWithinCurrentWeek(resolved, time);
				// 월간·주간 불일치 400의 필드 경로는 요청에서 쓴 형태(...TempId 또는 ...Id)로 낸다.
				String monthlyField = "actions[" + i + "]."
						+ (item.monthlyGoalTempId() != null ? "monthlyGoalTempId" : "monthlyGoalId");
				actionIds.add(actionService.insertInTx(userId, time, resolved, monthlyField));
			}

			// 성공하면 임시 저장을 지운다. 위 단계 중 하나라도 실패하면 여기까지 오지 못해 임시 저장이 그대로 남는다.
			draftRepository.deleteByUserId(userId);
			return new CompleteResponse(yearlyIds, monthlyIds, weeklyIds, actionIds);
		});
	}

	private static <T> List<T> orEmpty(List<T> list) {
		return list == null ? List.of() : list;
	}

	/** tempId는 필수이고 같은 단계 안에서 겹치면 안 된다. 다른 단계끼리는 같은 이름이어도 된다(참조 필드가 단계별로 다르다). */
	private static void checkTempId(String tempId, Set<String> seen, FieldErrors scope) {
		if (tempId == null || tempId.isBlank()) {
			scope.add("tempId", ValidationReason.REQUIRED);
		} else if (!seen.add(tempId)) {
			scope.add("tempId", ValidationReason.INVALID_FORMAT);
		}
	}

	/**
	 * 부모 지정은 ...TempId와 ...Id 중 정확히 하나다. 둘 다 보내면 ...TempId가 INVALID_FORMAT, 둘 다 없으면 ...Id가 REQUIRED,
	 * 요청 안에 없는 tempId를 가리키면 ...TempId가 INVALID_FORMAT이다.
	 * 부모 단계 목록을 먼저 훑은 뒤라 knownTemps가 채워져 있다(부모는 항상 앞 단계다).
	 */
	private static void requireOneParent(FieldErrors scope, String tempField, String tempRef, String idField,
			Long idRef, Set<String> knownTemps) {
		boolean hasTemp = tempRef != null;
		boolean hasId = idRef != null;
		if (hasTemp && hasId) {
			scope.add(tempField, ValidationReason.INVALID_FORMAT);
		} else if (!hasTemp && !hasId) {
			scope.add(idField, ValidationReason.REQUIRED);
		} else if (hasTemp && !knownTemps.contains(tempRef)) {
			scope.add(tempField, ValidationReason.INVALID_FORMAT);
		}
	}

	/** 행동의 월간·주간 참조. 둘 중 하나만 쓰고, tempId면 요청 안에 있어야 한다. 아무것도 없는 것은 허용한다(다른 쪽이 있으면 된다). */
	private static void checkOptionalParent(FieldErrors scope, String tempField, String tempRef, Long idRef,
			Set<String> knownTemps) {
		if (tempRef != null && idRef != null) {
			scope.add(tempField, ValidationReason.INVALID_FORMAT);
		} else if (tempRef != null && !knownTemps.contains(tempRef)) {
			scope.add(tempField, ValidationReason.INVALID_FORMAT);
		}
	}
}
