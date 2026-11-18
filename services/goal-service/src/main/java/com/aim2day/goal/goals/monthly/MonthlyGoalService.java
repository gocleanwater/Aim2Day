package com.aim2day.goal.goals.monthly;

import com.aim2day.goal.common.exception.BusinessException;
import com.aim2day.goal.common.exception.CommonErrorCode;
import com.aim2day.goal.common.exception.FieldErrors;
import com.aim2day.goal.common.exception.ValidationReason;
import com.aim2day.goal.common.exception.VersionGuard;
import com.aim2day.goal.common.tx.Tx;
import com.aim2day.goal.goals.GoalAssembler;
import com.aim2day.goal.goals.GoalErrorCode;
import com.aim2day.goal.goals.GoalFieldRules;
import com.aim2day.goal.goals.GoalStatus;
import com.aim2day.goal.goals.monthly.dto.MonthlyGoalDtos.CreateMonthlyGoalRequest;
import com.aim2day.goal.goals.monthly.dto.MonthlyGoalDtos.MonthlyGoalDetailResponse;
import com.aim2day.goal.goals.monthly.dto.MonthlyGoalDtos.MonthlyGoalResponse;
import com.aim2day.goal.goals.monthly.dto.MonthlyGoalDtos.UpdateMonthlyGoalRequest;
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
		this.tx = Tx.write(txManager);
		this.newTx = Tx.requiresNew(txManager);
		this.readTx = Tx.readOnly(txManager);
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
		NewGoal draft = validateNew(req.title(), req.description(), req.color(), errors);
		errors.throwIfAny();

		long yearlyGoalId = req.yearlyGoalId();
		Long id = tx.execute(status -> insertInTx(userId, yearlyGoalId, time, draft));
		return viewOf(userId, id, time.today());
	}

	/** 검증을 통과한 월간 목표 입력. color가 null이면 저장도 null이다(부모 색 상속). */
	public record NewGoal(String title, String description, String color) {
	}

	/** 제목·설명·색 규칙. 개별 생성과 온보딩 일괄 저장이 같은 규칙을 쓴다. */
	public static NewGoal validateNew(String title, String description, String color, FieldErrors errors) {
		// color가 null이면 저장도 null이다. 부모 색을 복사해 두면 부모 색이 바뀌어도 따라가지 못한다(GOAL-011).
		return new NewGoal(GoalFieldRules.title(title, errors), GoalFieldRules.description(description, errors),
				color == null ? null : GoalFieldRules.color(color, errors));
	}

	/**
	 * 부모 확인과 INSERT(현재 달). 호출자가 연 쓰기 트랜잭션 안에서 부른다. 온보딩은 같은 트랜잭션에서
	 * 방금 만든 연간 목표(flush됨)도 이 메서드로 부모 확인을 거친다. 새 월간 목표의 id를 돌려준다.
	 */
	public long insertInTx(long userId, long yearlyGoalId, UserTime time, NewGoal goal) {
		DateRange month = time.currentMonthRange();
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
		return repository.saveAndFlush(new MonthlyGoal(yearlyGoalId, month.startDate(), month.endDate(),
				goal.title(), goal.description(), goal.color())).getId();
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
		return readTx.execute(status -> detailView(userId, monthlyGoalId, time.today()));
	}

	/** 상세 응답 조립. 호출자 트랜잭션 안에서 부른다(조회는 읽기 전용 스냅샷, 복원은 쓰기 트랜잭션 안에서 응답을 완성). */
	public MonthlyGoalDetailResponse detailView(long userId, long monthlyGoalId, LocalDate today) {
		MonthlyGoal goal = findOwnedVisible(userId, monthlyGoalId);
		return assembler.monthlyTrees(List.of(goal), today).get(0);
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
			String title = GoalFieldRules.patchTitle(req.title(), goal.getTitle(), errors);
			String description = GoalFieldRules.patchDescription(req.description(), goal.getDescription(), errors);
			// color null은 "부모 색 상속으로 되돌림"이다.
			String color = GoalFieldRules.patchInheritableColor(req.color(), goal.getColor(), errors);
			GoalStatus newStatus = GoalFieldRules.patchStatus(req.status(), goal.getStatus(), errors);
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
		return VersionGuard.latestVersion(newTx,
				() -> repository.findById(monthlyGoalId).map(MonthlyGoal::getVersion).orElse(null),
				() -> notFound(monthlyGoalId));
	}
}
