package com.aim2day.goal.goals.trash;

import com.aim2day.goal.actions.ActionRepository;
import com.aim2day.goal.common.exception.BusinessException;
import com.aim2day.goal.common.lock.UserRowLock;
import com.aim2day.goal.common.time.UtcTime;
import com.aim2day.goal.common.tx.Tx;
import com.aim2day.goal.goals.GoalErrorCode;
import com.aim2day.goal.goals.monthly.MonthlyGoal;
import com.aim2day.goal.goals.monthly.MonthlyGoalRepository;
import com.aim2day.goal.goals.monthly.MonthlyGoalService;
import com.aim2day.goal.goals.trash.dto.TrashDtos.DeleteGoalResponse;
import com.aim2day.goal.goals.trash.dto.TrashDtos.TrashItemResponse;
import com.aim2day.goal.goals.weekly.WeeklyGoal;
import com.aim2day.goal.goals.weekly.WeeklyGoalRepository;
import com.aim2day.goal.goals.weekly.WeeklyGoalService;
import com.aim2day.goal.goals.yearly.YearlyGoal;
import com.aim2day.goal.goals.yearly.YearlyGoalRepository;
import com.aim2day.goal.goals.yearly.YearlyGoalService;
import com.aim2day.goal.progress.ProgressEngine;
import com.aim2day.goal.settings.SettingsService;
import com.aim2day.goal.settings.UserTime;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 목표 삭제·휴지통·복원(API 명세 §4.7, ERD §4.4).
 *
 * 잠금 순서(바꾸지 않는다): users 행 -> 대상 목표 행 -> (주간이면) 부모 월간 행.
 * 행동·좌표·수치 기록 쓰기도 첫 쿼리로 users 행을 잠그므로, 같은 사용자의 삭제·복원은 그 쓰기들과 한 줄로 선다.
 * 그래서 "삭제와 하위 행동 완료가 동시에" 오면 먼저 잠근 쪽이 먼저 반영된다(완료가 먼저면 완료 기록이 남고,
 * 삭제가 먼저면 완료 요청은 숨겨진 경로 404). 삭제 확인에 쓰는 하위 개수도 이 잠금 뒤에 세므로 확인 시점과 어긋나지 않는다.
 *
 * 삭제·복원은 deleted_at만 직접 UPDATE로 바꾼다. 하위 행·version은 바꾸지 않으므로(낙관적 락 대상 필드가 아니다)
 * VersionGuard 변환이 필요 없다. 응답(복원 상세)은 쓰기 트랜잭션 안에서 flush·clear 뒤 다시 읽어 완성한다.
 * 상태는 모두 DB에 있고 인스턴스 메모리에 두지 않는다.
 */
@Service
public class GoalTrashService {

	/** 복구 유예 기간(GOAL-008). 자동 정리 작업도 같은 값을 쓴다. */
	public static final int RETENTION_DAYS = 30;

	static final String DELETE_NOTICE = "30일 뒤 앞으로의 계획과 미완료 행동은 영구 삭제되고, "
			+ "완료한 목표·행동과 달성한 기준은 과거 이력으로 남습니다.";

	@PersistenceContext
	private EntityManager em;

	private final YearlyGoalRepository yearlies;
	private final MonthlyGoalRepository monthlies;
	private final WeeklyGoalRepository weeklies;
	private final ActionRepository actions;
	private final YearlyGoalService yearlyService;
	private final MonthlyGoalService monthlyService;
	private final WeeklyGoalService weeklyService;
	private final ProgressEngine engine;
	private final SettingsService settingsService;
	private final UserRowLock userRowLock;
	private final Clock clock;
	private final TransactionTemplate tx;
	/** 조회 전용. 세 단계 목록을 한 스냅샷으로 읽는다. */
	private final TransactionTemplate readTx;

	public GoalTrashService(YearlyGoalRepository yearlies, MonthlyGoalRepository monthlies,
			WeeklyGoalRepository weeklies, ActionRepository actions, YearlyGoalService yearlyService,
			MonthlyGoalService monthlyService, WeeklyGoalService weeklyService, ProgressEngine engine,
			SettingsService settingsService, UserRowLock userRowLock, Clock clock,
			PlatformTransactionManager txManager) {
		this.yearlies = yearlies;
		this.monthlies = monthlies;
		this.weeklies = weeklies;
		this.actions = actions;
		this.yearlyService = yearlyService;
		this.monthlyService = monthlyService;
		this.weeklyService = weeklyService;
		this.engine = engine;
		this.settingsService = settingsService;
		this.userRowLock = userRowLock;
		this.clock = clock;
		this.tx = Tx.write(txManager);
		this.readTx = Tx.readOnly(txManager);
	}

	/**
	 * 현재 시각을 초 단위로 자른다. deleted_at은 소수 초 없는 DATETIME이라 시계의 소수 초를 그대로 저장·응답하면
	 * 삭제 응답의 deletedAt·restoreDeadline이 이후 휴지통·복원·정리가 DB에서 읽어 계산하는 값과 달라진다.
	 * 삭제 저장·응답·기한 계산과 휴지통·복원·정리의 기준 시각 모두 이 값을 쓴다.
	 */
	static LocalDateTime truncate(LocalDateTime t) {
		return t.truncatedTo(ChronoUnit.SECONDS);
	}

	private LocalDateTime nowSeconds() {
		return truncate(UtcTime.now(clock));
	}

	// ---------------------------------------------------------------- 삭제

	/**
	 * 목표 삭제(DELETE /api/goals/{level}/{id}). 연·월·주간을 같은 규칙으로 소프트 삭제한다.
	 * 입력: 세션 userId, 목표 수준·id, confirm(하위 목표·미완료 행동이 함께 숨겨지는 것을 사용자가 확인했는지).
	 * 처리: 사용자 행을 먼저 잠그고 대상 행을 잠그며 소유·가시성을 확인한다. 보이는 하위 항목이 있는데 confirm이 아니면 409로 개수를 알려 준다.
	 * 삭제는 deleted_at만 기록하고 하위 행·version은 건드리지 않는다. 주간 목표를 지울 때만 월간 진행률의 분모가 바뀌므로 부모 월간을 다시 계산한다.
	 * 반환: 삭제 시각·복구 기한. 예외: 404 RESOURCE_NOT_FOUND, 409 GOAL_DELETE_CONFIRM_REQUIRED.
	 */
	public DeleteGoalResponse delete(long userId, GoalLevel level, long id, boolean confirm) {
		// 오늘(진행 기록 날짜)은 계정 시간대 기준이라 트랜잭션 밖에서 먼저 읽는다.
		UserTime time = settingsService.userTime(userId);
		return tx.execute(status -> {
			// 첫 쿼리: users 행 잠금(클래스 설명 참고).
			userRowLock.lock(userId);
			LocalDateTime now = nowSeconds();
			LocalDateTime deadline = now.plusDays(RETENTION_DAYS);

			// 대상 행 잠금과 소유·가시성 확인을 한 번에 한다. 없음·타인 소유·이미 삭제·조상 삭제로 숨김은 모두 404다.
			// 하위 개수는 "지금 보이는" 것만 센다(따로 삭제된 하위 목표와 그 행동은 이미 숨겨져 있어 다시 숨길 것이 없다).
			long monthlyCount;
			long weeklyCount;
			long incomplete;
			Long parentMonthlyId = null;
			String title;
			switch (level) {
				case YEARLY -> {
					YearlyGoal goal = yearlies.findOwnedForUpdate(id, userId)
							.orElseThrow(() -> YearlyGoalService.notFound(id));
					title = goal.getTitle();
					monthlyCount = monthlies.countByYearlyGoalIdAndDeletedAtIsNull(id);
					weeklyCount = weeklies.countActiveByYearly(id);
					incomplete = actions.countIncompleteUnderYearly(id);
				}
				case MONTHLY -> {
					MonthlyGoal goal = monthlies.findVisibleForUpdate(id, userId)
							.orElseThrow(() -> MonthlyGoalService.notFound(id));
					title = goal.getTitle();
					monthlyCount = 0;
					weeklyCount = weeklies.countByMonthlyGoalIdAndDeletedAtIsNull(id);
					incomplete = actions.countIncompleteUnderMonthly(id);
				}
				case WEEKLY -> {
					WeeklyGoal goal = weeklies.findVisibleForUpdate(id, userId)
							.orElseThrow(() -> WeeklyGoalService.notFound(id));
					title = goal.getTitle();
					parentMonthlyId = goal.getMonthlyGoalId();
					monthlyCount = 0;
					weeklyCount = 0;
					incomplete = actions.countIncompleteUnderWeekly(id);
				}
				default -> throw new IllegalStateException();
			}

			// 숨겨질 것이 하나라도 있는데 확인이 없으면 삭제하지 않고 화면이 확인 창을 띄울 정보를 돌려준다(GOAL-007).
			if (!confirm && (monthlyCount > 0 || weeklyCount > 0 || incomplete > 0)) {
				throw new BusinessException(GoalErrorCode.GOAL_DELETE_CONFIRM_REQUIRED, Map.of(
						"monthlyGoalCount", monthlyCount, "weeklyGoalCount", weeklyCount,
						"incompleteActionCount", incomplete,
						"restoreDeadline", UtcTime.toInstant(deadline).toString(), "notice", DELETE_NOTICE));
			}

			switch (level) {
				case YEARLY -> yearlies.setDeletedAt(id, now);
				case MONTHLY -> monthlies.setDeletedAt(id, now);
				case WEEKLY -> {
					weeklies.setDeletedAt(id, now);
					// 삭제된 주간 목표는 월간 진행률의 분모에서 빠지므로 부모 월간을 다시 계산한다(잠금 순서: 주간 -> 월간).
					// recalcMonthly가 먼저 flush하므로 위 UPDATE가 반영된 뒤 형제 주간을 읽는다.
					engine.recalcMonthly(userId, parentMonthlyId, time.today(), "주간 목표 삭제: " + title);
				}
				default -> throw new IllegalStateException();
			}
			return new DeleteGoalResponse(UtcTime.toInstant(now), UtcTime.toInstant(deadline));
		});
	}

	// ---------------------------------------------------------------- 휴지통

	/**
	 * 직접 삭제했고 기한이 남은 목표를 최근 삭제 순으로 돌려준다. 상위 목표 삭제로 숨겨진 하위 목표는 deleted_at이
	 * 없으므로 목록에 나오지 않는다. 기한이 지난 항목(정리 전이라도)은 복원할 수 없으므로 뺀다.
	 */
	public List<TrashItemResponse> trash(long userId) {
		UserTime time = settingsService.userTime(userId);
		LocalDateTime cutoff = nowSeconds().minusDays(RETENTION_DAYS);
		return readTx.execute(status -> {
			List<Entry> entries = new ArrayList<>();
			for (YearlyGoal y : yearlies.findInTrash(userId, cutoff)) {
				entries.add(new Entry(GoalLevel.YEARLY, y.getId(), y.getTitle(), String.valueOf(y.getTargetYear()),
						y.getDeletedAt(), null, false));
			}
			for (Object[] row : monthlies.findInTrash(userId, cutoff)) {
				MonthlyGoal m = (MonthlyGoal) row[0];
				YearlyGoal y = (YearlyGoal) row[1];
				entries.add(new Entry(GoalLevel.MONTHLY, m.getId(), m.getTitle(),
						String.format("%04d-%02d", m.getStartDate().getYear(), m.getStartDate().getMonthValue()),
						m.getDeletedAt(), new TrashItemResponse.Parent("YEARLY", y.getId(), y.getTitle()),
						y.getDeletedAt() != null));
			}
			for (Object[] row : weeklies.findInTrash(userId, cutoff)) {
				WeeklyGoal w = (WeeklyGoal) row[0];
				MonthlyGoal m = (MonthlyGoal) row[1];
				YearlyGoal y = (YearlyGoal) row[2];
				entries.add(new Entry(GoalLevel.WEEKLY, w.getId(), w.getTitle(),
						w.getStartDate() + "~" + w.getEndDate(), w.getDeletedAt(),
						new TrashItemResponse.Parent("MONTHLY", m.getId(), m.getTitle()),
						// 부모 월간 또는 그 조상 연간이 삭제 상태이면 복원 시 GOAL_PARENT_DELETED가 된다.
						m.getDeletedAt() != null || y.getDeletedAt() != null));
			}
			// 최근 삭제 순, 같으면 단계(연간 -> 월간 -> 주간), 그다음 id.
			entries.sort(Comparator.comparing(Entry::deletedAt).reversed()
					.thenComparing(e -> e.level().ordinal()).thenComparingLong(Entry::id));
			return entries.stream().map(e -> toItem(e, time)).toList();
		});
	}

	private record Entry(GoalLevel level, long id, String title, String periodLabel, LocalDateTime deletedAt,
			TrashItemResponse.Parent parent, boolean parentDeleted) {
	}

	private static TrashItemResponse toItem(Entry e, UserTime time) {
		LocalDateTime deadline = e.deletedAt().plusDays(RETENTION_DAYS);
		// 남은 일수 = 계정 시간대의 오늘부터 기한 날짜까지의 날짜 차(올림 없음). 기한이 오늘이면 0이다.
		LocalDate deadlineDate = deadline.toInstant(ZoneOffset.UTC).atZone(time.zone()).toLocalDate();
		long remaining = Math.max(0, ChronoUnit.DAYS.between(time.today(), deadlineDate));
		return new TrashItemResponse(e.level().name(), e.id(), e.title(), e.periodLabel(),
				UtcTime.toInstant(e.deletedAt()), UtcTime.toInstant(deadline), remaining, e.parent(),
				e.parentDeleted());
	}

	// ---------------------------------------------------------------- 복원

	/**
	 * 검사 순서: 404(타인·없음·삭제 상태가 아님) -> 409 GOAL_RESTORE_EXPIRED -> 409 GOAL_PARENT_DELETED ->
	 * 409 GOAL_LIMIT_EXCEEDED(연간만). 복원한 단계의 상세 응답(GET 상세와 같은 모양)을 돌려준다.
	 */
	public Object restore(long userId, GoalLevel level, long id) {
		UserTime time = settingsService.userTime(userId);
		return tx.execute(status -> {
			userRowLock.lock(userId);
			LocalDateTime now = nowSeconds();
			switch (level) {
				case YEARLY -> {
					YearlyGoal goal = yearlies.findDeletedForUpdate(id, userId)
							.orElseThrow(() -> YearlyGoalService.notFound(id));
					requireNotExpired(goal.getDeletedAt(), now);
					// 한도 확인은 users 행 잠금 뒤라 같은 사용자의 동시 생성·복원이 반영된 개수를 본다(단위 2와 같은 계산).
					long active = yearlies.countByUserIdAndTargetYearAndDeletedAtIsNull(userId, goal.getTargetYear());
					if (active >= YearlyGoalService.MAX_ACTIVE_PER_YEAR) {
						throw new BusinessException(GoalErrorCode.GOAL_LIMIT_EXCEEDED,
								Map.of("year", goal.getTargetYear(), "current", active,
										"max", YearlyGoalService.MAX_ACTIVE_PER_YEAR));
					}
					yearlies.setDeletedAt(id, null);
					em.flush();
					em.clear();
					return yearlyService.detailView(userId, id, time.today());
				}
				case MONTHLY -> {
					MonthlyGoal goal = monthlies.findDeletedForUpdate(id, userId)
							.orElseThrow(() -> MonthlyGoalService.notFound(id));
					requireNotExpired(goal.getDeletedAt(), now);
					YearlyGoal parent = yearlies.findById(goal.getYearlyGoalId()).orElseThrow();
					if (parent.getDeletedAt() != null) {
						throw parentDeleted("YEARLY", parent.getId());
					}
					monthlies.setDeletedAt(id, null);
					em.flush();
					em.clear();
					return monthlyService.detailView(userId, id, time.today());
				}
				case WEEKLY -> {
					WeeklyGoal goal = weeklies.findDeletedForUpdate(id, userId)
							.orElseThrow(() -> WeeklyGoalService.notFound(id));
					requireNotExpired(goal.getDeletedAt(), now);
					// 가장 가까운 삭제 상태 조상을 알려 준다(부모 월간 -> 그 위 연간 순).
					MonthlyGoal parent = monthlies.findById(goal.getMonthlyGoalId()).orElseThrow();
					if (parent.getDeletedAt() != null) {
						throw parentDeleted("MONTHLY", parent.getId());
					}
					YearlyGoal grand = yearlies.findById(parent.getYearlyGoalId()).orElseThrow();
					if (grand.getDeletedAt() != null) {
						throw parentDeleted("YEARLY", grand.getId());
					}
					weeklies.setDeletedAt(id, null);
					// 복원된 주간 목표가 월간 진행률의 분모에 다시 들어간다(잠금 순서: 주간 -> 월간).
					engine.recalcMonthly(userId, parent.getId(), time.today(), "주간 목표 복원: " + goal.getTitle());
					em.flush();
					em.clear();
					return weeklyService.detailView(userId, id, time.today());
				}
				default -> throw new IllegalStateException();
			}
		});
	}

	/** 기한(삭제 + 30일)이 지났으면 자동 정리가 아직 돌지 않았어도 거부한다. 기한 시각 당일까지는 복원할 수 있다. */
	private static void requireNotExpired(LocalDateTime deletedAt, LocalDateTime now) {
		LocalDateTime deadline = deletedAt.plusDays(RETENTION_DAYS);
		if (now.isAfter(deadline)) {
			Instant deleted = UtcTime.toInstant(deletedAt);
			throw new BusinessException(GoalErrorCode.GOAL_RESTORE_EXPIRED,
					Map.of("deletedAt", deleted.toString(), "restoreDeadline", UtcTime.toInstant(deadline).toString()));
		}
	}

	private static BusinessException parentDeleted(String parentLevel, long parentId) {
		return new BusinessException(GoalErrorCode.GOAL_PARENT_DELETED,
				Map.of("parentLevel", parentLevel, "parentId", parentId));
	}
}
