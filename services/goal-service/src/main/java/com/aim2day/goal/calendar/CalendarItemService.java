package com.aim2day.goal.calendar;

import com.aim2day.goal.calendar.dto.CalendarDtos.CalendarCompletionRequest;
import com.aim2day.goal.calendar.dto.CalendarDtos.CalendarItemResponse;
import com.aim2day.goal.calendar.dto.CalendarDtos.CreateCalendarItemRequest;
import com.aim2day.goal.calendar.dto.CalendarDtos.UpdateCalendarItemRequest;
import com.aim2day.goal.common.exception.BusinessException;
import com.aim2day.goal.common.exception.CommonErrorCode;
import com.aim2day.goal.common.exception.FieldErrors;
import com.aim2day.goal.common.exception.ValidationReason;
import com.aim2day.goal.common.exception.VersionGuard;
import com.aim2day.goal.common.lock.UserRowLock;
import com.aim2day.goal.common.time.UtcTime;
import com.aim2day.goal.common.tx.Tx;
import com.aim2day.goal.common.validation.TextRules;
import com.aim2day.goal.goals.GoalErrorCode;
import com.aim2day.goal.goals.yearly.YearlyGoalRepository;
import com.aim2day.goal.settings.SettingsService;
import com.aim2day.goal.settings.UserTime;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 달력 항목 생성·수정·완료·삭제(API 명세 §4.11).
 *
 * 잠금
 * - 생성·수정은 users 행을 트랜잭션 첫 쿼리로 잠근다(다른 쓰기와 같은 첫 잠금). 목표 삭제·복원도 users를 먼저 잠그므로
 *   "연결할 목표가 보이는지 확인한 뒤 저장하는 사이에 그 목표가 삭제되는" 경합이 직렬화된다. 달력 항목은 진행 기록을 남기지
 *   않아 다른 이유로는 users 잠금이 필요 없다.
 * - 수정·완료는 항목 행을 FOR UPDATE로 잠근 뒤 상태를 다시 읽는다. 같은 항목의 동시 요청이 한 줄로 서고, 늦게 온 쪽이
 *   먼저 온 쪽의 커밋을 본다(수정은 version 비교가 409로, 완료는 "이미 같은 상태"로 이어진다).
 * - 완료·삭제는 users를 잠그지 않는다. 연결 목표를 건드리지 않고, 항목 행 잠금만으로 같은 항목의 수정과 직렬화된다
 *   (수정은 users -> 항목 순서, 완료·삭제는 항목만이라 서로 반대 순서로 잠그는 일이 없다).
 *
 * 응답은 쓰기 트랜잭션 안에서 flush·clear 뒤 다시 읽어 완성한다(직접 UPDATE가 바꾼 완료 상태를 반영하고, 커밋 뒤 읽다가
 * 그 사이 다른 요청이 바꾼 값을 만나는 경합을 없애기 위해서다).
 */
@Service
public class CalendarItemService {

	private static final int TITLE_MAX = 255;
	private static final int DESCRIPTION_MAX = 2000;

	private final CalendarItemRepository items;
	private final YearlyGoalRepository yearlies;
	private final CalendarAssembler assembler;
	private final SettingsService settingsService;
	private final UserRowLock userRowLock;
	private final Clock clock;
	@PersistenceContext
	private EntityManager em;

	private final TransactionTemplate tx;
	private final TransactionTemplate newTx;

	public CalendarItemService(CalendarItemRepository items, YearlyGoalRepository yearlies,
			CalendarAssembler assembler, SettingsService settingsService, UserRowLock userRowLock, Clock clock,
			PlatformTransactionManager txManager) {
		this.items = items;
		this.yearlies = yearlies;
		this.assembler = assembler;
		this.settingsService = settingsService;
		this.userRowLock = userRowLock;
		this.clock = clock;
		this.tx = Tx.write(txManager);
		this.newTx = Tx.requiresNew(txManager);
	}

	/** 없음·타인 소유를 같은 404로 돌려 남의 항목이 존재하는지 알 수 없게 한다. */
	public static BusinessException notFound(long itemId) {
		return new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND, Map.of("resource", "CALENDAR_ITEM", "id", itemId));
	}

	private static BusinessException parentUnavailable(long goalId) {
		return new BusinessException(GoalErrorCode.GOAL_PARENT_UNAVAILABLE,
				Map.of("parentLevel", "YEARLY", "parentId", goalId));
	}

	// ---------------------------------------------------------------- 생성

	public CalendarItemResponse create(long userId, CreateCalendarItemRequest req) {
		// daysUntilDue 계산용 "오늘"은 트랜잭션 밖에서 먼저 읽는다(SettingsService는 자기 트랜잭션을 쓴다).
		UserTime time = settingsService.userTime(userId);

		FieldErrors errors = new FieldErrors();
		if (req.itemType() == null) {
			errors.add("itemType", ValidationReason.REQUIRED);
		}
		String title = title(req.title(), errors);
		String description = description(req.description(), errors);
		if (req.startDate() == null) {
			errors.add("startDate", ValidationReason.REQUIRED);
		}
		LocalDate endDate = null;
		if (req.itemType() != null && req.startDate() != null) {
			endDate = resolveEnd(req.itemType(), req.startDate(), req.endDate(), errors);
		}
		errors.throwIfAny();

		LocalDate end = endDate;
		return tx.execute(status -> {
			// 첫 쿼리는 users 행 잠금이다(클래스 설명).
			userRowLock.lock(userId);
			Long goalId = req.yearlyGoalId();
			if (goalId != null) {
				requireVisibleGoal(userId, goalId);
			}
			long id = items.saveAndFlush(new CalendarItem(userId, goalId, req.itemType(), title, description,
					req.startDate(), end)).getId();
			return freshView(userId, id, time.today());
		});
	}

	// ---------------------------------------------------------------- 수정

	public CalendarItemResponse update(long userId, long itemId, UpdateCalendarItemRequest req) {
		UserTime time = settingsService.userTime(userId);
		// 사전 비교를 통과한 뒤 다른 인스턴스가 먼저 커밋하는 경합은 @Version UPDATE가 막고, 트랜잭션 바깥에서 409로 바꾼다
		// (행 잠금을 쥐고 있어 실제로는 드물다). 쓰기 트랜잭션에는 격리 수준을 지정하지 않는다
		// (H2의 REPEATABLE READ는 낙관적 락 대신 H2 전용 예외를 낸다).
		return VersionGuard.translateConflict(() -> tx.execute(status -> {
			userRowLock.lock(userId);
			CalendarItem item = items.findOwnedForUpdate(itemId, userId).orElseThrow(() -> notFound(itemId));
			CalendarItemType type = item.getItemType();

			// ---- 필드 검증 (없음 = 유지, JSON null = 지움 또는 400)
			FieldErrors errors = new FieldErrors();
			String title = item.getTitle();
			if (req.title() != null) {
				if (req.title().isPresent()) {
					title = title(req.title().get(), errors);
				} else {
					errors.add("title", ValidationReason.REQUIRED);
				}
			}
			String description = item.getDescription();
			if (req.description() != null) {
				description = description(req.description().orElse(null), errors);
			}
			LocalDate start = item.getStartDate();
			if (req.startDate() != null) {
				if (req.startDate().isPresent()) {
					start = req.startDate().get();
				} else {
					errors.add("startDate", ValidationReason.REQUIRED);
				}
			}
			// EVENT는 endDate를 보내지 않으면 저장된 값을 유지한다. TASK는 저장된 종료일이 옛 마감일이라 시작일을 바꿀 때
			// 그대로 비교하면 항상 어긋나므로 보내지 않은 것을 "시작일과 같음"(null)으로 본다.
			// JSON null은 두 종류 모두 "하루 일정/마감일 = 시작일"이다.
			LocalDate rawEnd = req.endDate() != null ? req.endDate().orElse(null)
					: (type == CalendarItemType.TASK ? null : item.getEndDate());
			LocalDate end = resolveEnd(type, start, rawEnd, errors);
			Long goalId = item.getYearlyGoalId();
			boolean goalGiven = req.yearlyGoalId() != null;
			if (goalGiven) {
				goalId = req.yearlyGoalId().orElse(null);
			}
			boolean typeMismatch = false;
			if (req.itemType() != null) {
				if (req.itemType().isEmpty()) {
					errors.add("itemType", ValidationReason.REQUIRED);
				} else {
					typeMismatch = req.itemType().get() != type;
				}
			}
			errors.throwIfAny();

			VersionGuard.check(req.version(), item.getVersion());

			// 종류 변경은 막는다. 같은 종류를 다시 보내는 것은 무시한다.
			if (typeMismatch) {
				throw new BusinessException(CalendarErrorCode.CALENDAR_ITEM_TYPE_IMMUTABLE);
			}
			// 요청에 연결 목표가 있으면 보이는 목표인지 다시 확인한다(잠근 users 행 아래라 그 사이 삭제가 끼어들 수 없다).
			// 보내지 않았으면 기존 연결을 건드리지 않는다. 기존 목표가 삭제 상태여도 수정은 막지 않는다.
			if (goalGiven && goalId != null) {
				requireVisibleGoal(userId, goalId);
			}

			item.change(goalId, title, description, start, end);
			items.saveAndFlush(item);
			return freshView(userId, itemId, time.today());
		}), () -> currentVersion(itemId));
	}

	/** 롤백이 끝난 뒤 새 트랜잭션에서 최신 version을 읽는다. 실패한 트랜잭션의 영속성 컨텍스트는 오염돼 있다. */
	private long currentVersion(long itemId) {
		Long version = newTx.execute(status -> items.findById(itemId).map(CalendarItem::getVersion).orElse(null));
		if (version == null) {
			throw notFound(itemId);
		}
		return version;
	}

	// ---------------------------------------------------------------- 완료

	public CalendarItemResponse complete(long userId, long itemId, CalendarCompletionRequest req) {
		UserTime time = settingsService.userTime(userId);
		if (req.completed() == null) {
			throw new FieldErrors().add("completed", ValidationReason.REQUIRED).toException();
		}
		boolean completed = req.completed();
		return tx.execute(status -> {
			// 행 잠금 뒤 상태를 다시 읽는다. 같은 항목의 동시 요청 중 하나만 아래 변경을 하고 나머지는 같은 상태를 본다.
			CalendarItem item = items.findOwnedForUpdate(itemId, userId).orElseThrow(() -> notFound(itemId));
			if (item.isCompleted() != completed) {
				// 완료 시각은 초 단위로 자른다(MySQL DATETIME은 초 단위라 읽을 때와 값이 달라지지 않게). 취소하면 null이다.
				LocalDateTime at = completed ? UtcTime.now(clock).truncatedTo(ChronoUnit.SECONDS) : null;
				items.updateCompletion(itemId, completed, at);
			}
			// 같은 상태면 아무것도 바꾸지 않으므로 completedAt이 유지된다. version은 어느 쪽에서도 올리지 않는다.
			return freshView(userId, itemId, time.today());
		});
	}

	// ---------------------------------------------------------------- 삭제

	public void delete(long userId, long itemId) {
		tx.execute(status -> {
			// 소유자 조건을 건 DELETE라 남의 항목·없는 항목은 0행이다. 같은 항목의 수정이 행 잠금을 쥐고 있으면 끝날 때까지 기다린다.
			if (items.deleteOwned(itemId, userId) == 0) {
				throw notFound(itemId);
			}
			return null;
		});
	}

	// ---------------------------------------------------------------- 공통

	/** 본인 소유이고 삭제되지 않은 연간 목표만 연결할 수 있다. 상태(진행·보류 등)는 묻지 않는다. */
	private void requireVisibleGoal(long userId, long goalId) {
		if (yearlies.findByIdAndUserIdAndDeletedAtIsNull(goalId, userId).isEmpty()) {
			throw parentUnavailable(goalId);
		}
	}

	/**
	 * 종료일 규칙. TASK는 endDate를 생략하거나 startDate와 같아야 하고 저장은 endDate = startDate다.
	 * EVENT는 생략이면 하루, 있으면 startDate 이상이어야 한다.
	 */
	private static LocalDate resolveEnd(CalendarItemType type, LocalDate start, LocalDate end, FieldErrors errors) {
		if (end == null) {
			return start;
		}
		if (type == CalendarItemType.TASK) {
			if (!end.equals(start)) {
				errors.add("endDate", ValidationReason.INVALID_FORMAT);
			}
			return start;
		}
		if (end.isBefore(start)) {
			errors.add("endDate", ValidationReason.OUT_OF_RANGE);
		}
		return end;
	}

	/** 직접 UPDATE 뒤에는 영속성 컨텍스트가 낡았으므로 내보낸 뒤 비우고 다시 읽어 응답을 만든다. */
	private CalendarItemResponse freshView(long userId, long itemId, LocalDate today) {
		em.flush();
		em.clear();
		CalendarItem item = items.findById(itemId).orElseThrow();
		return assembler.responses(userId, List.of(item), today).get(0);
	}

	private static String title(String raw, FieldErrors errors) {
		return TextRules.requiredStrippedText(raw, "title", TITLE_MAX, errors);
	}

	private static String description(String raw, FieldErrors errors) {
		return TextRules.maxLength(raw, "description", DESCRIPTION_MAX, errors);
	}
}
