package com.aim2day.goal.goals.yearly;

import com.aim2day.goal.common.exception.BusinessException;
import com.aim2day.goal.common.exception.CommonErrorCode;
import com.aim2day.goal.common.exception.FieldErrors;
import com.aim2day.goal.common.exception.ValidationReason;
import com.aim2day.goal.common.exception.VersionGuard;
import com.aim2day.goal.common.lock.UserRowLock;
import com.aim2day.goal.goals.GoalErrorCode;
import com.aim2day.goal.goals.GoalFieldRules;
import com.aim2day.goal.goals.GoalAssembler;
import com.aim2day.goal.goals.GoalStatus;
import com.aim2day.goal.goals.criteria.CriteriaCalculator;
import com.aim2day.goal.goals.criteria.CriterionRepository;
import com.aim2day.goal.goals.criteria.YearlyGoalCriterion;
import com.aim2day.goal.goals.monthly.MonthlyGoalRepository;
import com.aim2day.goal.goals.yearly.dto.CreateYearlyGoalRequest;
import com.aim2day.goal.goals.yearly.dto.ReorderYearlyGoalsRequest;
import com.aim2day.goal.goals.yearly.dto.UpdateYearlyGoalRequest;
import com.aim2day.goal.goals.yearly.dto.YearlyGoalDetailResponse;
import com.aim2day.goal.goals.yearly.dto.YearlyGoalListResponse;
import com.aim2day.goal.goals.yearly.dto.YearlyGoalResponse;
import com.aim2day.goal.settings.SettingsService;
import com.aim2day.goal.settings.UserTime;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 연간 목표 생성·조회·수정·순서 저장. 상태를 인스턴스 메모리에 두지 않고 모두 DB에서 읽는다.
 * 트랜잭션은 TransactionTemplate으로 직접 연다. VersionGuard.translateConflict가 트랜잭션 바깥에서
 * 커밋 시점 충돌을 잡아야 하기 때문이다(SettingsService.update와 같은 구조).
 * "오늘"은 SettingsService.userTime()으로 얻고, 그 반환값은 읽기 전용이다.
 */
@Service
public class YearlyGoalService {

	/** 연도별 활성 연간 목표 최대 수(GOAL-001). */
	public static final int MAX_ACTIVE_PER_YEAR = 3;

	@PersistenceContext
	private EntityManager entityManager;

	private final YearlyGoalRepository repository;
	private final CriterionRepository criterionRepository;
	private final MonthlyGoalRepository monthlyRepository;
	private final GoalAssembler assembler;
	private final SettingsService settingsService;
	private final UserRowLock userRowLock;
	private final TransactionTemplate tx;
	private final TransactionTemplate newTx;
	/** 조회 전용. 목표와 기준을 한 스냅샷으로 읽도록 REPEATABLE READ를 명시한다(MySQL 기본값, H2에서도 같은 조건). */
	private final TransactionTemplate readTx;

	public YearlyGoalService(YearlyGoalRepository repository, CriterionRepository criterionRepository,
			MonthlyGoalRepository monthlyRepository, GoalAssembler assembler, SettingsService settingsService,
			UserRowLock userRowLock, PlatformTransactionManager txManager) {
		this.repository = repository;
		this.criterionRepository = criterionRepository;
		this.monthlyRepository = monthlyRepository;
		this.assembler = assembler;
		this.settingsService = settingsService;
		this.userRowLock = userRowLock;
		this.tx = new TransactionTemplate(txManager);
		this.newTx = new TransactionTemplate(txManager);
		this.newTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		this.readTx = new TransactionTemplate(txManager);
		this.readTx.setReadOnly(true);
		this.readTx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
	}

	/**
	 * 현재 사용자의 삭제되지 않은 연간 목표를 읽는다. 없음, 다른 사용자 소유, 소프트 삭제를 모두 같은 404로 돌려
	 * 남의 목표 id가 존재하는지 알 수 없게 한다(API 명세 §2.5). 단위 3의 월간 목표 생성도 부모 확인에 쓴다.
	 */
	public YearlyGoal findOwnedActive(long userId, long goalId) {
		return repository.findByIdAndUserIdAndDeletedAtIsNull(goalId, userId)
				.orElseThrow(() -> notFound(goalId));
	}

	public static BusinessException notFound(long goalId) {
		return new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND,
				Map.of("resource", "YEARLY_GOAL", "id", goalId));
	}

	public YearlyGoalResponse create(long userId, CreateYearlyGoalRequest req) {
		// 시간대 기준 "오늘"은 별도 트랜잭션에서 먼저 읽는다. 생성 트랜잭션의 첫 쿼리는 사용자 행 잠금이어야 한다.
		UserTime time = settingsService.userTime(userId);
		int thisYear = time.today().getYear();
		int year = req.targetYear() != null ? req.targetYear() : thisYear;

		FieldErrors errors = new FieldErrors();
		String title = GoalFieldRules.title(req.title(), errors);
		String description = GoalFieldRules.description(req.description(), errors);
		String color = req.color() == null ? GoalFieldRules.DEFAULT_COLOR : GoalFieldRules.color(req.color(), errors);
		// 현재 연도와 다음 연도만 허용한다(12월의 새해 계획용).
		if (year < thisYear || year > thisYear + 1) {
			errors.add("targetYear", ValidationReason.OUT_OF_RANGE);
		}
		errors.throwIfAny();

		YearlyGoal saved = tx.execute(status -> {
			// 첫 쿼리로 사용자 행을 잠가 같은 사용자의 동시 생성을 직렬화한다. 이후 개수 확인은 앞선 요청의 커밋을 본다.
			userRowLock.lock(userId);
			long active = repository.countByUserIdAndTargetYearAndDeletedAtIsNull(userId, year);
			if (active >= MAX_ACTIVE_PER_YEAR) {
				throw new BusinessException(GoalErrorCode.GOAL_LIMIT_EXCEEDED,
						year + "년 목표는 최대 " + MAX_ACTIVE_PER_YEAR
								+ "개까지 만들 수 있습니다. 기존 목표를 삭제한 뒤 다시 시도해 주세요.",
						Map.of("year", year, "current", active, "max", MAX_ACTIVE_PER_YEAR));
			}
			// 새 목표는 그해 목록의 마지막에 둔다. 삭제로 번호에 빈틈이 있어도 최댓값 + 1이면 항상 맨 뒤다.
			Integer maxOrder = repository.findMaxDisplayOrder(userId, year);
			int displayOrder = maxOrder == null ? 0 : maxOrder + 1;
			return repository.saveAndFlush(new YearlyGoal(userId, year, title, description, color, displayOrder));
		});
		return YearlyGoalResponse.of(saved, CriteriaCalculator.summary(saved, List.of()), time.today());
	}

	public YearlyGoalListResponse list(long userId, Integer requestedYear) {
		UserTime time = settingsService.userTime(userId);
		int year = requestedYear != null ? requestedYear : time.today().getYear();
		// 목표와 기준을 한 스냅샷으로 읽는다. 따로 읽으면 그 사이 다른 인스턴스의 확정·개정이 끼어
		// 목표는 옛 상태, 기준은 새 상태인 응답이 나온다.
		return readTx.execute(status -> {
			List<YearlyGoal> goals = repository
					.findByUserIdAndTargetYearAndDeletedAtIsNullOrderByDisplayOrderAscIdAsc(userId, year);
			Map<Long, List<YearlyGoalCriterion>> criteriaByGoal = new HashMap<>();
			if (!goals.isEmpty()) {
				for (YearlyGoalCriterion criterion : criterionRepository
						.findByYearlyGoalIdIn(goals.stream().map(YearlyGoal::getId).toList())) {
					criteriaByGoal.computeIfAbsent(criterion.getYearlyGoalId(), k -> new ArrayList<>()).add(criterion);
				}
			}
			List<YearlyGoalResponse> items = goals.stream()
					.map(g -> YearlyGoalResponse.of(g,
							CriteriaCalculator.summary(g, criteriaByGoal.getOrDefault(g.getId(), List.of())),
							time.today()))
					.toList();
			return new YearlyGoalListResponse(year, items.size(), MAX_ACTIVE_PER_YEAR, items);
		});
	}

	/**
	 * 카드 순서 저장. 목록은 그해 활성 목표 전체를 빠짐없이 한 번씩 담아야 하고, display_order를 0부터 다시 매긴다.
	 * 순서는 사용자가 고친 목표 필드가 아니므로 version을 올리지 않는다. 카드를 옮겼다고 열려 있는
	 * 목표 수정 화면이 409가 되면 안 된다.
	 */
	public YearlyGoalListResponse reorder(long userId, ReorderYearlyGoalsRequest req) {
		FieldErrors errors = new FieldErrors();
		if (req.year() == null) {
			errors.add("year", ValidationReason.REQUIRED);
		}
		if (req.goalIds() == null) {
			errors.add("goalIds", ValidationReason.REQUIRED);
		} else if (req.goalIds().contains(null)) {
			errors.add("goalIds", ValidationReason.INVALID_FORMAT);
		}
		errors.throwIfAny();
		int year = req.year();
		List<Long> requested = req.goalIds();

		tx.executeWithoutResult(status -> {
			userRowLock.lock(userId);
			List<Long> expected = new ArrayList<>(repository
					.findByUserIdAndTargetYearAndDeletedAtIsNullOrderByDisplayOrderAscIdAsc(userId, year).stream()
					.map(YearlyGoal::getId).toList());
			Collections.sort(expected);
			List<Long> sorted = new ArrayList<>(requested);
			Collections.sort(sorted);
			// 정렬해 비교하면 빠진 id, 남의 id, 중복 id가 모두 같은 규칙으로 걸러진다.
			if (!expected.equals(sorted)) {
				throw new BusinessException(GoalErrorCode.GOAL_ORDER_MISMATCH, Map.of("expectedIds", expected));
			}
			for (int i = 0; i < requested.size(); i++) {
				repository.updateDisplayOrder(requested.get(i), userId, i);
			}
		});
		return list(userId, year);
	}

	public YearlyGoalDetailResponse detail(long userId, long goalId) {
		UserTime time = settingsService.userTime(userId);
		return readTx.execute(status -> {
			YearlyGoal goal = findOwnedActive(userId, goalId);
			// 연간·월간·주간·기준을 모두 이 읽기 트랜잭션(한 스냅샷)에서 읽는다. 삭제되지 않은 하위 목표만 담는다.
			// 월간은 시작일, id 순이고 그 안의 주간도 시작일, id 순이다(조회 쿼리의 정렬).
			return new YearlyGoalDetailResponse(toResponse(goal, time.today()),
					assembler.monthlyTrees(monthlyRepository.findActiveByYearlyGoal(goalId), time.today()));
		});
	}

	public YearlyGoalResponse update(long userId, long goalId, UpdateYearlyGoalRequest req) {
		UserTime time = settingsService.userTime(userId);
		// 사전 비교를 통과한 뒤 다른 인스턴스가 먼저 커밋하면 @Version UPDATE가 충돌한다.
		// 트랜잭션 바깥에서 이를 409로 바꾸고 최신 version은 새 트랜잭션에서 읽는다(공유 계약 §5).
		// 수정 트랜잭션은 격리 수준을 지정하지 않는다. MySQL 기본이 REPEATABLE READ라 목표와 기준 읽기가 한 스냅샷이다.
		// H2에서 REPEATABLE READ를 명시하면 version이 바뀐 행의 UPDATE가 낙관적 락 예외가 아닌 동시 수정 예외로 나가
		// 409로 번역되지 않는다(H2 전용 동작).
		return VersionGuard.translateConflict(() -> tx.execute(status -> {
			YearlyGoal goal = findOwnedActive(userId, goalId);

			// 필드 없음(null)은 유지, JSON null(Optional.empty)은 지움이다. description만 지울 수 있다.
			FieldErrors errors = new FieldErrors();
			String title = goal.getTitle();
			if (req.title() != null) {
				if (req.title().isPresent()) {
					title = GoalFieldRules.title(req.title().get(), errors);
				} else {
					errors.add("title", ValidationReason.REQUIRED);
				}
			}
			String description = goal.getDescription();
			if (req.description() != null) {
				description = req.description().isPresent()
						? GoalFieldRules.description(req.description().get(), errors) : null;
			}
			String color = goal.getColor();
			if (req.color() != null) {
				if (req.color().isPresent()) {
					color = GoalFieldRules.color(req.color().get(), errors);
				} else {
					errors.add("color", ValidationReason.REQUIRED);
				}
			}
			GoalStatus newStatus = goal.getStatus();
			if (req.status() != null) {
				if (req.status().isPresent()) {
					newStatus = req.status().get();
				} else {
					errors.add("status", ValidationReason.REQUIRED);
				}
			}
			errors.throwIfAny();

			VersionGuard.check(req.version(), goal.getVersion());
			goal.change(title, description, color, newStatus);
			// flush해야 증가한 version과 DB가 갱신한 updated_at이 응답에 담긴다.
			repository.saveAndFlush(goal);
			// DB가 갱신한 updated_at을 응답에 담으려고 다시 읽는다(엔티티는 UPDATE 후 생성값을 읽지 않는다).
			entityManager.refresh(goal);
			return toResponse(goal, time.today());
		}), () -> currentVersion(goalId));
	}

	/** 롤백이 끝난 뒤 새 트랜잭션에서 최신 version을 읽는다. 실패한 트랜잭션의 컨텍스트는 오염돼 있다. */
	private long currentVersion(long goalId) {
		Long version = newTx.execute(status -> repository.findById(goalId).map(YearlyGoal::getVersion).orElse(null));
		if (version == null) {
			throw notFound(goalId);
		}
		return version;
	}

	private YearlyGoalResponse toResponse(YearlyGoal goal, LocalDate today) {
		List<YearlyGoalCriterion> criteria = criterionRepository.findByYearlyGoalIdOrderByIdAsc(goal.getId());
		return YearlyGoalResponse.of(goal, CriteriaCalculator.summary(goal, criteria), today);
	}
}
