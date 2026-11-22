package com.aim2day.goal.goals.criteria;

import com.aim2day.goal.common.exception.BusinessException;
import com.aim2day.goal.common.exception.CommonErrorCode;
import com.aim2day.goal.common.exception.FieldErrors;
import com.aim2day.goal.common.exception.ValidationReason;
import com.aim2day.goal.common.exception.VersionGuard;
import com.aim2day.goal.common.lock.UserRowLock;
import com.aim2day.goal.common.time.UtcTime;
import com.aim2day.goal.common.tx.Tx;
import com.aim2day.goal.common.validation.TextRules;
import com.aim2day.goal.goals.criteria.dto.CriteriaDtos.AchievementRequest;
import com.aim2day.goal.goals.criteria.dto.CriteriaDtos.AchievementResponse;
import com.aim2day.goal.goals.criteria.dto.CriteriaDtos.ConfirmCriteriaRequest;
import com.aim2day.goal.goals.criteria.dto.CriteriaDtos.CriteriaResponse;
import com.aim2day.goal.goals.criteria.dto.CriteriaDtos.SaveCriteriaRequest;
import com.aim2day.goal.goals.yearly.YearlyGoal;
import com.aim2day.goal.goals.yearly.YearlyGoalRepository;
import com.aim2day.goal.goals.yearly.YearlyGoalService;
import com.aim2day.goal.progress.ProgressChangeType;
import com.aim2day.goal.progress.ProgressLogWriter;
import com.aim2day.goal.progress.ProgressTargetType;
import com.aim2day.goal.settings.SettingsService;
import com.aim2day.goal.settings.UserTime;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 연간 목표 달성 기준 조회·저장(초안·개정)·확정·달성.
 *
 * 동시성 규칙: 쓰기 경로는 모두 "users 행 -> 연간 목표 행" 순서로 FOR UPDATE 잠금을 잡는다.
 *  - 연간 목표 행 잠금: 기준 저장·확정·달성이 서로 겹쳐 달성률이 저장된 기준과 어긋나는 것을 막는다.
 *    달성률은 저장하지 않고 기준 행에서 계산하지만, 이전 달성률(진행 기록의 previous_rate)을 읽은 뒤
 *    다른 요청이 기준을 바꾸면 기록이 틀어지기 때문이다.
 *  - users 행을 먼저 잠그는 이유: 연간 목표 순서 저장과 목표 생성이 users -> 목표 순서로 잠근다.
 *    기준 경로가 목표 행만 잡고 진행 기록 INSERT(users FK 확인이 users 행을 공유 잠금)로 가면,
 *    순서 저장과 서로 기다리는 교착이 MySQL에서 생길 수 있다. 같은 순서로 맞춰 막는다.
 * 목표 version은 기준을 저장·확정할 때만 올리고 달성에서는 올리지 않는다(API 명세 §2.4).
 * version은 엔티티 저장이 아니라 직접 UPDATE로 올린다. 행 잠금을 쥔 채 사전 비교를 마쳤으므로 충돌 없이 +1이 된다.
 */
@Service
public class CriteriaService {

	static final int REQUIRED_TOTAL_WEIGHT = 100;
	static final int MAX_ITEMS = 20;
	static final int TEXT_MAX = 255;
	static final int MEMO_MAX = 255;

	private final YearlyGoalRepository goals;
	private final CriterionRepository criteria;
	private final SettingsService settingsService;
	private final UserRowLock userRowLock;
	private final ProgressLogWriter progressLogs;
	private final Clock clock;
	private final TransactionTemplate tx;
	private final TransactionTemplate newTx;
	/** 조회 전용. REPEATABLE READ를 명시해 한 트랜잭션 안의 읽기가 같은 스냅샷을 보게 한다. */
	private final TransactionTemplate readTx;

	public CriteriaService(YearlyGoalRepository goals, CriterionRepository criteria, SettingsService settingsService,
			UserRowLock userRowLock, ProgressLogWriter progressLogs, Clock clock,
			PlatformTransactionManager txManager) {
		this.goals = goals;
		this.criteria = criteria;
		this.settingsService = settingsService;
		this.userRowLock = userRowLock;
		this.progressLogs = progressLogs;
		this.clock = clock;
		this.tx = Tx.write(txManager);
		this.newTx = Tx.requiresNew(txManager);
		this.readTx = Tx.readOnly(txManager);
	}

	/**
	 * 연간 목표의 달성 기준 조회(GET /api/goals/yearly/{id}/criteria).
	 * 처리: 목표와 기준 목록을 읽기 전용 트랜잭션 하나에서 읽어 확정 상태·version과 항목이 같은 시점의 값이 되게 한다.
	 * 예외: 없거나 남의 목표이거나 삭제됐으면 404 RESOURCE_NOT_FOUND.
	 */
	public CriteriaResponse get(long userId, long goalId) {
		// 목표 행과 기준 목록을 한 스냅샷으로 읽는다. 따로 읽으면 그 사이 확정·개정이 끼어
		// state·version은 옛 값, 항목은 새 값인 응답이 나온다.
		return readTx.execute(status -> {
			YearlyGoal goal = goals.findByIdAndUserIdAndDeletedAtIsNull(goalId, userId)
					.orElseThrow(() -> YearlyGoalService.notFound(goalId));
			return CriteriaResponse.of(goal, criteria.findByYearlyGoalIdOrderByIdAsc(goalId));
		});
	}

	/**
	 * 기준 목록 전체 교체. 확정 전이면 초안 저장(배점 합계 검사 없음), 확정 후면 개정(합계 정확히 100,
	 * 달성 여부 유지, CRITERIA_REVISION 기록). 검사 순서: 404 -> 400 -> 409 -> 422.
	 */
	public CriteriaResponse save(long userId, long goalId, SaveCriteriaRequest req) {
		UserTime time = settingsService.userTime(userId);
		LocalDateTime now = UtcTime.now(clock);
		return VersionGuard.translateConflict(() -> tx.execute(status -> {
			userRowLock.lock(userId);
			YearlyGoal goal = lockOwnedGoal(userId, goalId);
			List<YearlyGoalCriterion> existing = criteria.findByYearlyGoalIdOrderByIdAsc(goalId);
			Map<Long, YearlyGoalCriterion> existingById = new HashMap<>();
			existing.forEach(c -> existingById.put(c.getId(), c));

			validateSave(req, existingById.keySet());
			VersionGuard.check(req.goalVersion(), goal.getVersion());

			int total = req.items().stream().mapToInt(SaveCriteriaRequest.Item::weight).sum();
			boolean confirmed = goal.isCriteriaConfirmed();
			if (confirmed && total != REQUIRED_TOTAL_WEIGHT) {
				throw weightSumInvalid(total);
			}

			// 개정 전 달성률. 확정 전에는 달성한 기준이 없어 0이다.
			int previousRate = CriteriaCalculator.achievementRate(existing);

			// id가 있으면 수정(달성 여부 유지), 없으면 새로 만들고(미달성), 목록에서 빠진 기존 항목은 지운다.
			List<YearlyGoalCriterion> result = new ArrayList<>();
			Set<Long> keptIds = new HashSet<>();
			for (SaveCriteriaRequest.Item item : req.items()) {
				String text = item.text().strip();
				if (item.id() != null) {
					YearlyGoalCriterion criterion = existingById.get(item.id());
					criterion.revise(text, item.weight());
					keptIds.add(item.id());
					result.add(criterion);
				} else {
					result.add(criteria.save(new YearlyGoalCriterion(goalId, text, item.weight())));
				}
			}
			criteria.deleteAll(existing.stream().filter(c -> !keptIds.contains(c.getId())).toList());
			criteria.flush();

			if (confirmed) {
				goals.markCriteriaRevised(goalId, now);
				// 개정은 전후 달성률이 같아도 반드시 남긴다. 리포트가 "기준 변경으로 비교 불가" 기간을 판단하는 근거다.
				progressLogs.record(userId, ProgressTargetType.YEARLY_GOAL, goalId,
						BigDecimal.valueOf(previousRate), BigDecimal.valueOf(CriteriaCalculator.achievementRate(result)),
						ProgressChangeType.CRITERIA_REVISION, time.today(), req.memo());
			} else {
				// 초안 저장도 기준 목록이 바뀌므로 목표 version을 올린다. 열려 있는 다른 화면의 오래된 저장을 막는다.
				goals.bumpVersion(goalId);
			}
			return view(goalId);
		}), () -> currentVersion(goalId));
	}

	/** 초안을 확정하고 최초 기준선을 저장한다. 검사 순서: 404 -> 409(이미 확정) -> 409(version) -> 400 -> 422. */
	public CriteriaResponse confirm(long userId, long goalId, ConfirmCriteriaRequest req) {
		UserTime time = settingsService.userTime(userId);
		LocalDateTime now = UtcTime.now(clock);
		return VersionGuard.translateConflict(() -> tx.execute(status -> {
			userRowLock.lock(userId);
			YearlyGoal goal = lockOwnedGoal(userId, goalId);
			if (req.goalVersion() == null) {
				throw new FieldErrors().add("goalVersion", ValidationReason.REQUIRED).toException();
			}
			if (goal.isCriteriaConfirmed()) {
				throw new BusinessException(CriteriaErrorCode.CRITERIA_ALREADY_CONFIRMED);
			}
			VersionGuard.check(req.goalVersion(), goal.getVersion());

			List<YearlyGoalCriterion> existing = criteria.findByYearlyGoalIdOrderByIdAsc(goalId);
			Map<Long, YearlyGoalCriterion> existingById = new LinkedHashMap<>();
			existing.forEach(c -> existingById.put(c.getId(), c));
			List<Long> baselineIds = req.baselineAchievedCriterionIds() == null ? List.of()
					: req.baselineAchievedCriterionIds();
			// 이 목표의 기준이 아닌 id, 같은 id 중복은 400이다. 남의 기준 id를 baseline에 넣어 달성 표시를 건드리게 둘 수 없다.
			Set<Long> seen = new HashSet<>();
			for (Long id : baselineIds) {
				if (id == null || !existingById.containsKey(id) || !seen.add(id)) {
					throw new FieldErrors().add("baselineAchievedCriterionIds", ValidationReason.INVALID_FORMAT).toException();
				}
			}

			int total = CriteriaCalculator.totalWeight(existing);
			// 기준이 하나도 없으면 합계가 0이라 이 검사에서 함께 걸러진다.
			if (total != REQUIRED_TOTAL_WEIGHT) {
				throw weightSumInvalid(total);
			}

			int baselineRate = 0;
			for (Long id : baselineIds) {
				YearlyGoalCriterion criterion = existingById.get(id);
				criterion.markBaselineAchieved(now);
				baselineRate += criterion.getWeight();
			}
			criteria.flush();
			goals.confirmCriteria(goalId, baselineRate, now);
			// 최초 관측값: 이전 0, 새 값 = 기준선. 이후 달성·개정 기록이 이 값에서 이어진다.
			progressLogs.record(userId, ProgressTargetType.YEARLY_GOAL, goalId, BigDecimal.ZERO,
					BigDecimal.valueOf(baselineRate), ProgressChangeType.PROGRESS_UPDATE, time.today(), null);
			return view(goalId);
		}), () -> currentVersion(goalId));
	}

	/**
	 * 기준 달성·취소. 같은 값을 다시 보내면 아무것도 바꾸지 않고 기록도 남기지 않는다(멱등).
	 * 목표 version은 받지도 올리지도 않는다. 달성은 "상태 지정" 요청이고 목표 필드가 바뀌는 것이 아니다.
	 */
	public AchievementResponse achieve(long userId, long goalId, long criterionId, AchievementRequest req) {
		UserTime time = settingsService.userTime(userId);
		LocalDateTime now = UtcTime.now(clock);
		return tx.execute(status -> {
			userRowLock.lock(userId);
			YearlyGoal goal = lockOwnedGoal(userId, goalId);
			// 기준이 이 목표의 것인지 함께 조회한다. 남의 목표의 기준, 내 다른 목표의 기준은 모두 404다.
			YearlyGoalCriterion criterion = criteria.findByIdAndYearlyGoalId(criterionId, goalId)
					.orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND,
							Map.of("resource", "CRITERION", "id", criterionId)));
			if (!goal.isCriteriaConfirmed()) {
				throw new BusinessException(CriteriaErrorCode.CRITERIA_NOT_CONFIRMED);
			}
			boolean achieved = req.achieved();
			// 행 잠금을 쥔 상태에서 읽으므로 이 값이 이전 달성률로 확정이다.
			List<YearlyGoalCriterion> all = criteria.findByYearlyGoalIdOrderByIdAsc(goalId);
			int previousRate = CriteriaCalculator.achievementRate(all);
			if (criterion.isAchieved() == achieved) {
				return new AchievementResponse(criterionId, achieved, previousRate);
			}
			if (achieved) {
				criterion.achieve(now);
			} else {
				criterion.unachieve();
			}
			criteria.flush();
			// all의 criterion은 위에서 바꾼 같은 영속 객체라 다시 계산하면 새 달성률이 된다.
			int newRate = CriteriaCalculator.achievementRate(all);
			progressLogs.record(userId, ProgressTargetType.YEARLY_GOAL, goalId, BigDecimal.valueOf(previousRate),
					BigDecimal.valueOf(newRate), ProgressChangeType.PROGRESS_UPDATE, time.today(), req.memo());
			return new AchievementResponse(criterionId, achieved, newRate);
		});
	}

	/** users 행을 잠근 뒤 목표 행을 잠근다. 없음·타인·삭제는 같은 404다. */
	private YearlyGoal lockOwnedGoal(long userId, long goalId) {
		return goals.findOwnedForUpdate(goalId, userId).orElseThrow(() -> YearlyGoalService.notFound(goalId));
	}

	/** 직접 UPDATE로 영속성 컨텍스트가 비워졌으므로 DB의 최신 목표·기준을 다시 읽어 응답을 만든다. */
	private CriteriaResponse view(long goalId) {
		YearlyGoal fresh = goals.findById(goalId).orElseThrow(() -> YearlyGoalService.notFound(goalId));
		return CriteriaResponse.of(fresh, criteria.findByYearlyGoalIdOrderByIdAsc(goalId));
	}

	private long currentVersion(long goalId) {
		Long version = newTx.execute(status -> goals.findById(goalId).map(YearlyGoal::getVersion).orElse(null));
		if (version == null) {
			throw YearlyGoalService.notFound(goalId);
		}
		return version;
	}

	private static BusinessException weightSumInvalid(int total) {
		return new BusinessException(CriteriaErrorCode.CRITERIA_WEIGHT_SUM_INVALID,
				Map.of("totalWeight", total, "required", REQUIRED_TOTAL_WEIGHT));
	}

	/** 항목 수·길이·범위와 기준 id(이 목표의 기준인지, 중복이 아닌지)를 검증한다. */
	private static void validateSave(SaveCriteriaRequest req, Set<Long> existingIds) {
		FieldErrors errors = new FieldErrors();
		if (req.goalVersion() == null) {
			errors.add("goalVersion", ValidationReason.REQUIRED);
		}
		TextRules.maxLength(req.memo(), "memo", MEMO_MAX, errors);
		List<SaveCriteriaRequest.Item> items = req.items();
		if (items == null) {
			errors.add("items", ValidationReason.REQUIRED);
			errors.throwIfAny();
		}
		if (items.isEmpty() || items.size() > MAX_ITEMS) {
			errors.add("items", ValidationReason.OUT_OF_RANGE);
		}
		Set<Long> seen = new HashSet<>();
		for (int i = 0; i < items.size(); i++) {
			SaveCriteriaRequest.Item item = items.get(i);
			String prefix = "items[" + i + "]";
			if (item == null) {
				errors.add(prefix, ValidationReason.INVALID_FORMAT);
				continue;
			}
			String text = TextRules.requiredStrippedText(item.text(), prefix + ".text", TEXT_MAX, errors);
			if (item.weight() == null) {
				errors.add(prefix + ".weight", ValidationReason.REQUIRED);
			} else if (item.weight() < 1 || item.weight() > 100) {
				errors.add(prefix + ".weight", ValidationReason.OUT_OF_RANGE);
			}
			if (item.id() != null && (!existingIds.contains(item.id()) || !seen.add(item.id()))) {
				errors.add(prefix + ".id", ValidationReason.INVALID_FORMAT);
			}
		}
		errors.throwIfAny();
	}
}
