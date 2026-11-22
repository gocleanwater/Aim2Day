package com.aim2day.goal.goals.weekly;

import com.aim2day.goal.actions.ActionAssembler;
import com.aim2day.goal.actions.ActionRepository;
import com.aim2day.goal.common.exception.BusinessException;
import com.aim2day.goal.common.exception.CommonErrorCode;
import com.aim2day.goal.common.exception.FieldErrors;
import com.aim2day.goal.common.exception.ValidationReason;
import com.aim2day.goal.common.exception.VersionGuard;
import com.aim2day.goal.common.lock.UserRowLock;
import com.aim2day.goal.common.time.DateRange;
import com.aim2day.goal.common.tx.Tx;
import com.aim2day.goal.goals.DecimalRules;
import com.aim2day.goal.goals.GoalAssembler;
import com.aim2day.goal.goals.GoalErrorCode;
import com.aim2day.goal.goals.GoalFieldRules;
import com.aim2day.goal.goals.GoalStatus;
import com.aim2day.goal.goals.monthly.MonthlyGoal;
import com.aim2day.goal.goals.monthly.MonthlyGoalRepository;
import com.aim2day.goal.goals.weekly.dto.WeeklyGoalDtos.CreateWeeklyGoalRequest;
import com.aim2day.goal.goals.weekly.dto.WeeklyGoalDtos.UpdateWeeklyGoalRequest;
import com.aim2day.goal.goals.weekly.dto.WeeklyGoalDtos.WeeklyGoalDetailResponse;
import com.aim2day.goal.goals.weekly.dto.WeeklyGoalDtos.WeeklyGoalResponse;
import com.aim2day.goal.progress.ProgressEngine;
import com.aim2day.goal.settings.SettingsService;
import com.aim2day.goal.settings.UserTime;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 주간 목표 생성·조회·수정. 소유 확인은 월간 -> 연간 목표까지 올라가 user_id로 한다(ERD 5.1-4).
 *
 * 잠금 순서는 users -> 주간 -> 월간이다. 진행률에 영향을 주는 쓰기(생성, 목표값·가중치·부모 월간 변경)는
 * 진행 기록을 남길 수 있고, 기록 INSERT가 users 행에 공유 잠금을 거는 FK 확인을 하므로 트랜잭션 첫 쿼리로
 * users 행을 잠근다(UserRowLock). users 행을 잠그는 다른 경로(연간 생성·순서 저장·기준 저장)와 순서를 맞춰
 * 서로 기다리는 교착을 막고, 같은 사용자의 진행률 변경을 한 줄로 세워 ProgressEngine이 앞선 요청의 커밋을 보게 한다.
 * 제목·설명·색·상태만 바꾸는 수정은 진행률과 무관하므로 users 행을 잠그지 않는다(낙관적 락으로 충분하다).
 */
@Service
public class WeeklyGoalService {

	private static final BigDecimal DEFAULT_WEIGHT = new BigDecimal("1.00");

	private final WeeklyGoalRepository repository;
	private final MonthlyGoalRepository monthlyRepository;
	private final GoalAssembler assembler;
	private final ActionRepository actionRepository;
	private final ActionAssembler actionAssembler;
	private final ProgressEngine engine;
	private final SettingsService settingsService;
	private final UserRowLock userRowLock;
	private final TransactionTemplate tx;
	private final TransactionTemplate newTx;
	/** 조회 전용. 여러 테이블을 읽어 한 응답을 만들므로 REPEATABLE READ로 한 스냅샷을 보게 한다. */
	private final TransactionTemplate readTx;

	public WeeklyGoalService(WeeklyGoalRepository repository, MonthlyGoalRepository monthlyRepository,
			GoalAssembler assembler, ActionRepository actionRepository, ActionAssembler actionAssembler,
			ProgressEngine engine, SettingsService settingsService,
			UserRowLock userRowLock, PlatformTransactionManager txManager) {
		this.repository = repository;
		this.monthlyRepository = monthlyRepository;
		this.assembler = assembler;
		this.actionRepository = actionRepository;
		this.actionAssembler = actionAssembler;
		this.engine = engine;
		this.settingsService = settingsService;
		this.userRowLock = userRowLock;
		this.tx = Tx.write(txManager);
		this.newTx = Tx.requiresNew(txManager);
		this.readTx = Tx.readOnly(txManager);
	}

	/** 없음·타인 소유·삭제·조상 삭제를 모두 같은 404로 돌려 남의 목표가 존재하는지 알 수 없게 한다. */
	public WeeklyGoal findOwnedVisible(long userId, long weeklyGoalId) {
		return repository.findVisible(weeklyGoalId, userId).orElseThrow(() -> notFound(weeklyGoalId));
	}

	/** 주간 목표가 없을 때 쓰는 404 예외를 만든다. */
	public static BusinessException notFound(long weeklyGoalId) {
		return new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND,
				Map.of("resource", "WEEKLY_GOAL", "id", weeklyGoalId));
	}

	/**
	 * 주간 목표를 만든다(POST /api/goals/weekly). 주간 목표는 계정 설정 기준 현재 주로만 만들 수 있다.
	 * 입력: 세션 userId, 상위 월간 목표 id, 제목·설명·색·측정 방식·가중치·목표값.
	 * 처리: 검증(400) 후 쓰기 트랜잭션에서 사용자 행을 먼저 잠그고 부모 확인·INSERT·월간 진행률 재계산을 한다.
	 * 반환: 만들어진 주간 목표 응답. 예외: 422 GOAL_PARENT_UNAVAILABLE, 422 GOAL_PARENT_PERIOD_MISMATCH.
	 */
	public WeeklyGoalResponse create(long userId, CreateWeeklyGoalRequest req) {
		// "이번 주"는 생성 시점의 계정 설정(시간대·주 시작 요일)으로 계산한다. 트랜잭션 밖에서 먼저 읽는다.
		UserTime time = settingsService.userTime(userId);

		FieldErrors errors = new FieldErrors();
		if (req.monthlyGoalId() == null) {
			errors.add("monthlyGoalId", ValidationReason.REQUIRED);
		}
		NewGoal draft = validateNew(req.title(), req.description(), req.color(), req.measurementType(), req.weight(),
				req.targetValue(), errors);
		errors.throwIfAny();

		long monthlyGoalId = req.monthlyGoalId();
		Long id = tx.execute(status -> {
			// 첫 쿼리는 users 행 잠금이다(위 클래스 설명).
			userRowLock.lock(userId);
			return insertInTx(userId, monthlyGoalId, time, draft);
		});
		return viewOf(userId, id, time.today());
	}

	/** 검증을 통과한 주간 목표 입력. type은 기본 CHECKLIST, weight는 기본 1.00, target은 수치형일 때만 있다. */
	public record NewGoal(String title, String description, String color, MeasurementType type, BigDecimal weight,
			BigDecimal target) {
	}

	/** 제목·설명·색·측정 방식·가중치·목표값 규칙. 개별 생성과 온보딩 일괄 저장이 같은 규칙을 쓴다. */
	public static NewGoal validateNew(String title, String description, String color, MeasurementType measurementType,
			BigDecimal rawWeight, BigDecimal rawTarget, FieldErrors errors) {
		String checkedTitle = GoalFieldRules.title(title, errors);
		String checkedDescription = GoalFieldRules.description(description, errors);
		String checkedColor = color == null ? null : GoalFieldRules.color(color, errors);
		MeasurementType type = measurementType == null ? MeasurementType.CHECKLIST : measurementType;
		BigDecimal weight = rawWeight == null ? DEFAULT_WEIGHT
				: DecimalRules.positive(rawWeight, DecimalRules.MAX_WEIGHT, "weight", errors);
		BigDecimal target = null;
		if (type == MeasurementType.NUMERIC) {
			target = DecimalRules.positive(rawTarget, DecimalRules.MAX_VALUE, "targetValue", errors);
		} else if (rawTarget != null) {
			// 체크형에는 목표값이 없다.
			errors.add("targetValue", ValidationReason.INVALID_FORMAT);
		}
		return new NewGoal(checkedTitle, checkedDescription, checkedColor, type, weight, target);
	}

	/**
	 * 부모 확인과 INSERT(이번 주)와 월간 재계산. 호출자가 연 쓰기 트랜잭션 안에서, users 행 잠금을 건 뒤에 부른다.
	 * 새 주간 목표의 id를 돌려준다.
	 */
	public long insertInTx(long userId, long monthlyGoalId, UserTime time, NewGoal goal) {
		DateRange week = time.currentWeek();
		// 부모 월간 목표 확인과 행 잠금을 한 번에 한다. INSERT의 FK 확인이 월간 행에 공유 잠금을 걸기 전에
		// 배타 잠금을 먼저 잡아야, 뒤이은 월간 재계산이 공유 -> 배타 승격을 기다리다 교착하지 않는다.
		MonthlyGoal parent = monthlyRepository.findVisibleForUpdate(monthlyGoalId, userId)
				.orElseThrow(() -> parentUnavailable(monthlyGoalId));
		requireOverlap(parent, week.startDate(), week.endDate());
		Long weeklyId = repository.saveAndFlush(new WeeklyGoal(monthlyGoalId, week.startDate(), week.endDate(),
				goal.title(), goal.description(), goal.color(), goal.type(), goal.target(), goal.weight())).getId();
		// 새 주간 목표는 0%로 분모에 들어가므로 월간 진행률을 다시 계산한다.
		engine.recalcMonthly(userId, monthlyGoalId, time.today(), "주간 목표 추가: " + goal.title());
		return weeklyId;
	}

	/**
	 * 주간 목표 목록을 만든다(GET /api/goals/weekly).
	 * 입력: 세션 userId, date(null이면 오늘), monthlyGoalId(null이면 전체).
	 * 처리: 그 날짜가 기간에 드는 내게 보이는 주간 목표를 읽고 월간 목표 필터를 적용한다.
	 */
	public List<WeeklyGoalResponse> list(long userId, LocalDate date, Long monthlyGoalId) {
		UserTime time = settingsService.userTime(userId);
		LocalDate target = date != null ? date : time.today();
		return readTx.execute(status -> {
			List<WeeklyGoal> goals = repository.findVisibleByDate(userId, target).stream()
					.filter(w -> monthlyGoalId == null || w.getMonthlyGoalId().equals(monthlyGoalId))
					.toList();
			return assembler.weeklyResponses(goals, time.today());
		});
	}

	/**
	 * 주간 목표 상세를 만든다(GET /api/goals/weekly/{id}). 읽기 전용 트랜잭션에서 연결 행동 목록까지 담는다.
	 * 예외: 없거나 남의 목표이거나 숨겨졌으면 404 RESOURCE_NOT_FOUND.
	 */
	public WeeklyGoalDetailResponse detail(long userId, long weeklyGoalId) {
		UserTime time = settingsService.userTime(userId);
		return readTx.execute(status -> detailView(userId, weeklyGoalId, time.today()));
	}

	/**
	 * 상세 응답 조립. 호출자 트랜잭션 안에서 부른다(조회는 읽기 전용 스냅샷, 복원은 쓰기 트랜잭션 안에서 응답을 완성).
	 * 연결 행동은 삭제되지 않은 것만 수행일, id 순이다. 목표와 행동을 같은 트랜잭션에서 읽는다.
	 */
	public WeeklyGoalDetailResponse detailView(long userId, long weeklyGoalId, LocalDate today) {
		WeeklyGoal goal = findOwnedVisible(userId, weeklyGoalId);
		return WeeklyGoalDetailResponse.of(assembler.weeklyResponses(List.of(goal), today).get(0),
				actionAssembler.responses(actionRepository.findActiveByWeeklyGoals(List.of(weeklyGoalId)), today));
	}

	/**
	 * 주간 목표를 부분 수정한다(PATCH /api/goals/weekly/{id}).
	 * 입력: 세션 userId, 목표 id, 바꿀 필드와 클라이언트가 본 version.
	 * 처리: 목표값·가중치·상위 월간 목표가 바뀌면 사용자 행 잠금을 먼저 잡고 진행률을 다시 계산한다. 주간 행 잠금 뒤 version을 비교한다.
	 * 반환: 수정된 목표 응답. 예외: 400 검증 실패, 404 없음, 409 VERSION_CONFLICT, 422 GOAL_MEASUREMENT_TYPE_IMMUTABLE 등.
	 */
	public WeeklyGoalResponse update(long userId, long weeklyGoalId, UpdateWeeklyGoalRequest req) {
		UserTime time = settingsService.userTime(userId);
		// 목표값·가중치·부모 월간이 바뀌면 진행률을 다시 계산하고 기록을 남길 수 있다.
		boolean affectsProgress = req.targetValue() != null || req.weight() != null || req.monthlyGoalId() != null;
		// 사전 비교를 통과한 뒤 다른 인스턴스가 먼저 커밋하는 경합은 @Version UPDATE가 막고, 트랜잭션 바깥에서
		// 409로 바꾼다. 쓰기 트랜잭션에는 격리 수준을 지정하지 않는다(H2의 REPEATABLE READ는 낙관적 락 대신
		// H2 전용 예외를 낸다).
		VersionGuard.translateConflict(() -> tx.execute(status -> {
			if (affectsProgress) {
				userRowLock.lock(userId);
			}
			// 주간 행 잠금. 읽은 값이 최신이라 엔티티의 version과 현재값이 서로 어긋나지 않는다.
			WeeklyGoal goal = repository.findVisibleForUpdate(weeklyGoalId, userId)
					.orElseThrow(() -> notFound(weeklyGoalId));

			FieldErrors errors = new FieldErrors();
			String title = GoalFieldRules.patchTitle(req.title(), goal.getTitle(), errors);
			String description = GoalFieldRules.patchDescription(req.description(), goal.getDescription(), errors);
			// color null은 "부모 색 상속으로 되돌림"이다.
			String color = GoalFieldRules.patchInheritableColor(req.color(), goal.getColor(), errors);
			GoalStatus newStatus = GoalFieldRules.patchStatus(req.status(), goal.getStatus(), errors);
			BigDecimal weight = goal.getWeight();
			if (req.weight() != null) {
				if (req.weight().isPresent()) {
					BigDecimal checked = DecimalRules.positive(req.weight().get(), DecimalRules.MAX_WEIGHT,
							"weight", errors);
					weight = checked != null ? checked : weight;
				} else {
					errors.add("weight", ValidationReason.REQUIRED);
				}
			}
			boolean numeric = goal.getMeasurementType() == MeasurementType.NUMERIC;
			BigDecimal targetValue = goal.getTargetValue();
			// 체크형에 목표값을 보내는 것은 형식 오류가 아니라 업무 규칙 위반(422)이라 아래에서 따로 거른다.
			if (req.targetValue() != null && numeric) {
				if (req.targetValue().isPresent()) {
					BigDecimal checked = DecimalRules.positive(req.targetValue().get(), DecimalRules.MAX_VALUE,
							"targetValue", errors);
					targetValue = checked != null ? checked : targetValue;
				} else {
					errors.add("targetValue", ValidationReason.REQUIRED);
				}
			}
			if (req.monthlyGoalId() != null && req.monthlyGoalId().isEmpty()) {
				errors.add("monthlyGoalId", ValidationReason.REQUIRED);
			}
			if (req.measurementType() != null && req.measurementType().isEmpty()) {
				errors.add("measurementType", ValidationReason.REQUIRED);
			}
			errors.throwIfAny();

			VersionGuard.check(req.version(), goal.getVersion());

			if (req.measurementType() != null && req.measurementType().get() != goal.getMeasurementType()) {
				throw new BusinessException(GoalErrorCode.GOAL_MEASUREMENT_TYPE_IMMUTABLE);
			}
			// 필드 존재 여부로 거른다. 명시적 null({"targetValue": null})도 체크형에는 보낼 수 없는 필드를 보낸 것이다.
			if (!numeric && req.targetValue() != null) {
				throw new BusinessException(GoalErrorCode.GOAL_NOT_NUMERIC);
			}

			long oldMonthlyId = goal.getMonthlyGoalId();
			long newMonthlyId = oldMonthlyId;
			if (req.monthlyGoalId() != null && req.monthlyGoalId().get() != oldMonthlyId) {
				newMonthlyId = req.monthlyGoalId().get();
				moveParentLocks(userId, goal, oldMonthlyId, newMonthlyId);
			}

			goal.change(title, description, color, newStatus, targetValue, weight, newMonthlyId);
			repository.saveAndFlush(goal);

			if (affectsProgress) {
				// 목표값·가중치 변경은 진행률과 월간 평균에, 이동은 이전·새 월간 모두에 영향을 준다.
				// 이동하면 recalcWeekly가 새 부모를, 아래 줄이 이전 부모를 다시 계산한다.
				engine.recalcWeekly(userId, weeklyGoalId, time.today(), "주간 목표 수정: " + title);
				if (newMonthlyId != oldMonthlyId) {
					engine.recalcMonthly(userId, oldMonthlyId, time.today(), "주간 목표 이동: " + title);
				}
			}
			return null;
		}), () -> currentVersion(weeklyGoalId));
		return viewOf(userId, weeklyGoalId, time.today());
	}

	/**
	 * 이전·새 월간 목표를 id 오름차순으로 잠근다. 두 월간을 서로 다른 순서로 잠그는 요청 둘이 만나면 교착하므로
	 * 순서를 고정한다. 새 부모는 잠그면서 가시성도 다시 확인한다(잠그기 전의 확인은 그 사이 삭제를 못 잡는다).
	 */
	private void moveParentLocks(long userId, WeeklyGoal goal, long oldMonthlyId, long newMonthlyId) {
		MonthlyGoal newParent = null;
		for (long id : oldMonthlyId < newMonthlyId ? new long[] {oldMonthlyId, newMonthlyId}
				: new long[] {newMonthlyId, oldMonthlyId}) {
			if (id == newMonthlyId) {
				newParent = monthlyRepository.findVisibleForUpdate(id, userId)
						.orElseThrow(() -> parentUnavailable(newMonthlyId));
			} else {
				engine.lockMonthly(id);
			}
		}
		// 주간 목표의 기간은 바뀌지 않는다. 새 월간 목표가 그 기간과 하루라도 겹쳐야 한다.
		requireOverlap(newParent, goal.getStartDate(), goal.getEndDate());
	}

	private static BusinessException parentUnavailable(long monthlyGoalId) {
		return new BusinessException(GoalErrorCode.GOAL_PARENT_UNAVAILABLE,
				Map.of("parentLevel", "MONTHLY", "parentId", monthlyGoalId));
	}

	/** 월·연도 경계 주는 겹치는 월간 목표 중 하나에 귀속된다. 하루도 안 겹치면 422다(GOAL-006). */
	private static void requireOverlap(MonthlyGoal parent, LocalDate weekStart, LocalDate weekEnd) {
		if (weekEnd.isBefore(parent.getStartDate()) || weekStart.isAfter(parent.getEndDate())) {
			throw new BusinessException(GoalErrorCode.GOAL_PARENT_PERIOD_MISMATCH,
					Map.of("parentStartDate", parent.getStartDate().toString(),
							"parentEndDate", parent.getEndDate().toString()));
		}
	}

	private WeeklyGoalResponse viewOf(long userId, long weeklyGoalId, LocalDate today) {
		return readTx.execute(status -> assembler
				.weeklyResponses(List.of(findOwnedVisible(userId, weeklyGoalId)), today).get(0));
	}

	/** 롤백이 끝난 뒤 새 트랜잭션에서 최신 version을 읽는다. 실패한 트랜잭션의 영속성 컨텍스트는 오염돼 있다. */
	private long currentVersion(long weeklyGoalId) {
		return VersionGuard.latestVersion(newTx,
				() -> repository.findById(weeklyGoalId).map(WeeklyGoal::getVersion).orElse(null),
				() -> notFound(weeklyGoalId));
	}
}
