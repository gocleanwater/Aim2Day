package com.aim2day.goal.goals.monthly;

import com.aim2day.goal.common.exception.BusinessException;
import com.aim2day.goal.common.exception.CommonErrorCode;
import com.aim2day.goal.common.exception.FieldErrors;
import com.aim2day.goal.common.exception.ValidationReason;
import com.aim2day.goal.common.exception.VersionGuard;
import com.aim2day.goal.goals.GoalAssembler;
import com.aim2day.goal.goals.GoalErrorCode;
import com.aim2day.goal.goals.GoalFieldRules;
import com.aim2day.goal.goals.GoalStatus;
import com.aim2day.goal.goals.monthly.dto.CreateMonthlyGoalRequest;
import com.aim2day.goal.goals.monthly.dto.MonthlyGoalDetailResponse;
import com.aim2day.goal.goals.monthly.dto.MonthlyGoalResponse;
import com.aim2day.goal.goals.monthly.dto.UpdateMonthlyGoalRequest;
import com.aim2day.goal.goals.yearly.YearlyGoal;
import com.aim2day.goal.goals.yearly.YearlyGoalRepository;
import com.aim2day.goal.common.time.DateRange;
import com.aim2day.goal.settings.SettingsService;
import com.aim2day.goal.settings.UserTime;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 월간 목표 생성·조회·수정. 소유 확인은 연간 목표까지 올라가 user_id로 한다(ERD 5.1-4).
 * 자신이나 조상 중 하나라도 삭제됐으면 일반 API에서 404다(API 명세 §2.5).
 * 월간 목표 생성·수정은 진행률을 바꾸지 않고(하위 주간 목표가 없거나 필드가 진행률과 무관) 진행 기록도 남기지 않으므로
 * users 행을 잠그지 않는다. 부모 연간 목표 행에는 FK 확인이 공유 잠금만 건다.
 */
@Service
public class MonthlyGoalService {

	private final MonthlyGoalRepository repository;
	private final YearlyGoalRepository yearlyRepository;
	private final GoalAssembler assembler;
	private final SettingsService settingsService;
	private final TransactionTemplate tx;
	private final TransactionTemplate newTx;
	/** 조회 전용. 여러 테이블을 읽어 한 응답을 만들므로 REPEATABLE READ로 한 스냅샷을 보게 한다. */
	private final TransactionTemplate readTx;

	public MonthlyGoalService(MonthlyGoalRepository repository, YearlyGoalRepository yearlyRepository,
			GoalAssembler assembler, SettingsService settingsService, PlatformTransactionManager txManager) {
		this.repository = repository;
		this.yearlyRepository = yearlyRepository;
		this.assembler = assembler;
		this.settingsService = settingsService;
		this.tx = new TransactionTemplate(txManager);
		this.newTx = new TransactionTemplate(txManager);
		this.newTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		this.readTx = new TransactionTemplate(txManager);
		this.readTx.setReadOnly(true);
		this.readTx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
	}

	/** 없음·타인 소유·삭제·조상 삭제를 모두 같은 404로 돌려 남의 목표가 존재하는지 알 수 없게 한다. */
	public MonthlyGoal findOwnedVisible(long userId, long monthlyGoalId) {
		return repository.findVisible(monthlyGoalId, userId).orElseThrow(() -> notFound(monthlyGoalId));
	}

	public static BusinessException notFound(long monthlyGoalId) {
		return new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND,
				Map.of("resource", "MONTHLY_GOAL", "id", monthlyGoalId));
	}

	public MonthlyGoalResponse create(long userId, CreateMonthlyGoalRequest req) {
		// 현재 달은 계정 시간대 기준이라 먼저 읽는다(트랜잭션 밖).
		UserTime time = settingsService.userTime(userId);

		FieldErrors errors = new FieldErrors();
		if (req.yearlyGoalId() == null) {
			errors.add("yearlyGoalId", ValidationReason.REQUIRED);
		}
		String title = GoalFieldRules.title(req.title(), errors);
		String description = GoalFieldRules.description(req.description(), errors);
		// color가 null이면 저장도 null이다. 부모 색을 복사해 두면 부모 색이 바뀌어도 따라가지 못한다(GOAL-011).
		String color = req.color() == null ? null : GoalFieldRules.color(req.color(), errors);
		errors.throwIfAny();

		long yearlyGoalId = req.yearlyGoalId();
		DateRange month = time.currentMonthRange();
		Long id = tx.execute(status -> {
			// 부모가 없음·타인 소유·삭제는 구분하지 않는다. 존재 여부를 드러내지 않기 위해서다.
			YearlyGoal parent = yearlyRepository.findByIdAndUserIdAndDeletedAtIsNull(yearlyGoalId, userId)
					.orElseThrow(() -> new BusinessException(GoalErrorCode.GOAL_PARENT_UNAVAILABLE,
							Map.of("parentLevel", "YEARLY", "parentId", yearlyGoalId)));
			// 월간 목표는 현재 달로 만들므로 부모 연간 목표가 올해여야 기간이 겹친다.
			if (parent.getTargetYear() != time.currentMonth().getYear()) {
				throw new BusinessException(GoalErrorCode.GOAL_PARENT_PERIOD_MISMATCH,
						Map.of("parentStartDate", LocalDate.of(parent.getTargetYear(), 1, 1).toString(),
								"parentEndDate", LocalDate.of(parent.getTargetYear(), 12, 31).toString()));
			}
			return repository.saveAndFlush(
					new MonthlyGoal(yearlyGoalId, month.startDate(), month.endDate(), title, description, color))
					.getId();
		});
		return viewOf(userId, id, time.today());
	}

	public List<MonthlyGoalResponse> list(long userId, String monthParam, Long yearlyGoalId) {
		UserTime time = settingsService.userTime(userId);
		YearMonth month = time.currentMonth();
		if (monthParam != null) {
			try {
				month = YearMonth.parse(monthParam);
			} catch (DateTimeException e) {
				throw new FieldErrors().add("month", ValidationReason.INVALID_FORMAT).toException();
			}
		}
		LocalDate monthStart = month.atDay(1);
		return readTx.execute(status -> {
			List<MonthlyGoal> goals = repository.findVisibleByMonth(userId, monthStart).stream()
					.filter(m -> yearlyGoalId == null || m.getYearlyGoalId().equals(yearlyGoalId))
					.toList();
			return assembler.monthlyResponses(goals, time.today());
		});
	}

	public MonthlyGoalDetailResponse detail(long userId, long monthlyGoalId) {
		UserTime time = settingsService.userTime(userId);
		return readTx.execute(status -> {
			MonthlyGoal goal = findOwnedVisible(userId, monthlyGoalId);
			return assembler.monthlyTrees(List.of(goal), time.today()).get(0);
		});
	}

	public MonthlyGoalResponse update(long userId, long monthlyGoalId, UpdateMonthlyGoalRequest req) {
		UserTime time = settingsService.userTime(userId);
		// 사전 비교를 통과한 뒤 다른 인스턴스가 먼저 커밋하는 경합은 @Version UPDATE가 막고,
		// 트랜잭션 바깥에서 409로 바꾼다. 쓰기 트랜잭션에는 격리 수준을 지정하지 않는다(H2의 REPEATABLE READ는
		// 낙관적 락 대신 H2 전용 예외를 내므로).
		VersionGuard.translateConflict(() -> tx.execute(status -> {
			MonthlyGoal goal = findOwnedVisible(userId, monthlyGoalId);

			// 필드 없음(null)은 유지, JSON null(Optional.empty)은 지움이다. description·color만 지울 수 있다.
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
			// color null은 "부모 색 상속으로 되돌림"이다.
			String color = goal.getColor();
			if (req.color() != null) {
				color = req.color().isPresent() ? GoalFieldRules.color(req.color().get(), errors) : null;
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
			repository.saveAndFlush(goal);
			return null;
		}), () -> currentVersion(monthlyGoalId));
		return viewOf(userId, monthlyGoalId, time.today());
	}

	private MonthlyGoalResponse viewOf(long userId, long monthlyGoalId, LocalDate today) {
		return readTx.execute(status -> assembler
				.monthlyResponses(List.of(findOwnedVisible(userId, monthlyGoalId)), today).get(0));
	}

	/** 롤백이 끝난 뒤 새 트랜잭션에서 최신 version을 읽는다. 실패한 트랜잭션의 영속성 컨텍스트는 오염돼 있다. */
	private long currentVersion(long monthlyGoalId) {
		Long version = newTx.execute(status -> repository.findById(monthlyGoalId).map(MonthlyGoal::getVersion)
				.orElse(null));
		if (version == null) {
			throw notFound(monthlyGoalId);
		}
		return version;
	}
}
