package com.aim2day.goal.quadrant;

import com.aim2day.goal.actions.Action;
import com.aim2day.goal.actions.ActionRepository;
import com.aim2day.goal.actions.ActionService;
import com.aim2day.goal.actions.ActionType;
import com.aim2day.goal.common.exception.BusinessException;
import com.aim2day.goal.common.exception.FieldErrors;
import com.aim2day.goal.common.exception.ValidationReason;
import com.aim2day.goal.common.lock.UserRowLock;
import com.aim2day.goal.common.time.UtcTime;
import com.aim2day.goal.quadrant.dto.ConfirmResponse;
import com.aim2day.goal.quadrant.dto.PlacementRequest;
import com.aim2day.goal.quadrant.dto.PlacementResponse;
import com.aim2day.goal.settings.SettingsService;
import com.aim2day.goal.settings.UserTime;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.math.BigInteger;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 사분면 좌표 쓰기(API 명세 §4.10: PUT placement, DELETE placement/pending, POST placements/confirm).
 *
 * 잠금 순서: users 행(트랜잭션 첫 쿼리) -> 행동 행(PUT·DELETE). 행동 쓰기와 같은 순서라 교착이 없다.
 * - users 행 잠금 덕분에 같은 사용자의 분류 요청이 두 인스턴스에서 겹쳐도 차례로 처리된다. 같은 행동을 동시에 PUT해도
 *   "행이 없으면 만든다"가 겹치지 않아 UNIQUE (action_id, classification_date) 위반 500이 나지 않고, 마지막에 잠금을 얻은
 *   요청의 값이 남는다. 확정과 PUT이 겹쳐도 확정은 자기가 본 행만 옮기므로 결과와 남은 pending이 어긋나지 않는다
 *   (PUT이 먼저면 그 pending이 확정되고, 나중이면 확정 뒤 새 pending이 남는다).
 * - 행동 행을 FOR UPDATE로 잠근 뒤 상태(삭제·수행일)를 다시 읽는다. 수행일을 옮기는 PATCH가 같은 행동 행을 잠그므로
 *   "오늘인지"는 잠근 뒤의 수행일로 판단한다.
 * "오늘"은 트랜잭션 밖에서 SettingsService.userTime으로 먼저 구한다(설정 행 확보가 자기 트랜잭션을 쓴다).
 * 응답은 쓰기 트랜잭션 안에서 flush·clear 뒤 다시 읽어 완성한다(커밋 뒤 별도 트랜잭션 재조회 금지).
 */
@Service
public class QuadrantService {

	private static final int MIN = 0;
	private static final int MAX = 100;
	/** 목표 연결 행동의 최소 중요도(ERD 4.5, FOCUS-016). */
	private static final int GOAL_MIN_IMPORTANCE = 50;

	private final ActionRepository actions;
	private final QuadrantPlacementRepository placements;
	private final ConfirmedDateStore confirmedDates;
	private final SettingsService settingsService;
	private final UserRowLock userRowLock;
	private final Clock clock;
	@PersistenceContext
	private EntityManager em;

	private final TransactionTemplate tx;

	public QuadrantService(ActionRepository actions, QuadrantPlacementRepository placements,
			ConfirmedDateStore confirmedDates, SettingsService settingsService, UserRowLock userRowLock, Clock clock,
			PlatformTransactionManager txManager) {
		this.actions = actions;
		this.placements = placements;
		this.confirmedDates = confirmedDates;
		this.settingsService = settingsService;
		this.userRowLock = userRowLock;
		this.clock = clock;
		this.tx = new TransactionTemplate(txManager);
	}

	// ---------------------------------------------------------------- PUT placement

	public PlacementResponse place(long userId, long actionId, PlacementRequest req) {
		// 1. 입력 검증(400)이 404보다 먼저다. DB를 읽기 전에 끝낸다.
		Integer[] coordinate = validate(req);
		UserTime time = settingsService.userTime(userId);

		return tx.execute(status -> {
			userRowLock.lock(userId);
			// 2. 없음·타인 소유·삭제·경로 삭제는 모두 404다.
			Action action = actions.findWritableForUpdate(actionId, userId)
					.orElseThrow(() -> ActionService.notFound(actionId));
			// 3. 오늘 수행일인 행동만 분류한다.
			requireToday(action, time.today());
			// 4. 목표 연결 행동은 중요도 50 이상만(좌표를 보낼 때만 검사한다. 트레이로 되돌리기는 제한이 없다).
			if (coordinate != null && action.getType() == ActionType.GOAL && coordinate[0] < GOAL_MIN_IMPORTANCE) {
				throw new BusinessException(QuadrantErrorCode.QUADRANT_OUT_OF_RANGE,
						Map.of("minImportance", GOAL_MIN_IMPORTANCE));
			}

			QuadrantPlacement row = placements.findByActionIdAndClassificationDate(actionId, time.today())
					.orElse(null);
			if (coordinate != null) {
				if (row == null) {
					row = new QuadrantPlacement(actionId, time.today());
					placements.save(row);
				}
				row.placePending(coordinate[0], coordinate[1]);
			} else if (row != null) {
				// 트레이로 되돌리기: 확정 좌표가 없으면 행을 지워 바로 미분류, 있으면 확정 전까지 되돌리기 대기다.
				// 이미 대기 중이면 같은 값을 다시 쓰는 것이라 변화가 없다(200).
				if (row.hasConfirmed()) {
					row.markPendingUnclassify();
				} else {
					placements.delete(row);
				}
			}
			return view(actionId, time.today());
		});
	}

	// ---------------------------------------------------------------- DELETE placement/pending

	public PlacementResponse cancelPending(long userId, long actionId) {
		UserTime time = settingsService.userTime(userId);
		return tx.execute(status -> {
			userRowLock.lock(userId);
			Action action = actions.findWritableForUpdate(actionId, userId)
					.orElseThrow(() -> ActionService.notFound(actionId));
			// 가정: 지난 날짜의 변경은 취소할 수 없다(결정 18: 확인·취소·확정 방법이 없다)는 규칙에 맞춰 PUT과 같은 422다.
			requireToday(action, time.today());
			QuadrantPlacement row = placements.findByActionIdAndClassificationDate(actionId, time.today())
					.orElse(null);
			if (row != null) {
				if (row.hasConfirmed()) {
					row.clearPending();
				} else {
					// 확정 좌표가 없던 행동은 미분류가 된다.
					placements.delete(row);
				}
			}
			return view(actionId, time.today());
		});
	}

	// ---------------------------------------------------------------- POST placements/confirm

	public ConfirmResponse confirm(long userId) {
		// 설정 행을 먼저 확보한다(없으면 만든다). 확정일 UPDATE가 행을 못 찾아 조용히 0건이 되는 일을 막는다.
		UserTime time = settingsService.userTime(userId);
		LocalDate today = time.today();

		return tx.execute(status -> {
			userRowLock.lock(userId);
			// 잠금을 얻은 뒤 읽으므로 앞서 커밋된 PUT·DELETE의 결과가 모두 보이고, 이 확정이 끝나기 전에는
			// 같은 사용자의 새 PUT이 시작하지 못한다.
			List<QuadrantPlacement> rows = placements.findConfirmable(userId, today);
			int confirmed = 0;
			int unclassified = 0;
			for (QuadrantPlacement row : rows) {
				if (row.hasPending()) {
					row.confirmPending(UtcTime.now(clock));
					confirmed++;
				} else if (row.isPendingUnclassify()) {
					// 되돌리기 대기는 행을 지워 미분류로 만든다.
					placements.delete(row);
					unclassified++;
				}
				// 미확정 변경이 없는 행(확정 상태)은 그대로 둔다.
			}
			// 변경이 0건이어도 확정일을 오늘로 기록한다(confirmedToday). 직접 UPDATE라 설정 version은 오르지 않는다.
			em.flush();
			confirmedDates.write(userId, today);
			return new ConfirmResponse(today, confirmed, unclassified);
		});
	}

	// ---------------------------------------------------------------- 내부

	private static void requireToday(Action action, LocalDate today) {
		if (!action.getPerformedDate().equals(today)) {
			throw new BusinessException(QuadrantErrorCode.QUADRANT_DATE_INVALID,
					Map.of("today", today.toString(), "performedDate", action.getPerformedDate().toString()));
		}
	}

	/** 쓰기 뒤 응답 완성: 직접 변경분을 flush하고 영속성 컨텍스트를 비운 뒤 같은 트랜잭션에서 다시 읽는다. */
	private PlacementResponse view(long actionId, LocalDate today) {
		em.flush();
		em.clear();
		QuadrantPlacement row = placements.findByActionIdAndClassificationDate(actionId, today).orElse(null);
		return new PlacementResponse(actionId, PlacementViews.of(row, true));
	}

	/**
	 * 두 필드 검증. 반환값은 {importance, urgency}이고 둘 다 null이면(트레이로 되돌리기) null을 돌려준다.
	 * 필드 누락·하나만 null·정수가 아님·0~100 밖은 모두 400 VALIDATION_FAILED다.
	 */
	private static Integer[] validate(PlacementRequest req) {
		FieldErrors errors = new FieldErrors();
		if (!req.importancePresent()) {
			errors.add("importance", ValidationReason.REQUIRED);
		}
		if (!req.urgencyPresent()) {
			errors.add("urgency", ValidationReason.REQUIRED);
		}
		errors.throwIfAny();

		Object importance = req.importance();
		Object urgency = req.urgency();
		if (importance == null && urgency == null) {
			return null;
		}
		// 하나만 null이면 null인 쪽이 빠진 것과 같다.
		if (importance == null) {
			errors.add("importance", ValidationReason.REQUIRED);
		}
		if (urgency == null) {
			errors.add("urgency", ValidationReason.REQUIRED);
		}
		Integer x = importance == null ? null : integer("importance", importance, errors);
		Integer y = urgency == null ? null : integer("urgency", urgency, errors);
		errors.throwIfAny();
		return new Integer[] {x, y};
	}

	/** 0~100 정수만 통과한다. 소수(72.5, 72.0 포함)·문자열·불린은 INVALID_FORMAT, 범위 밖은 OUT_OF_RANGE다. */
	private static Integer integer(String field, Object value, FieldErrors errors) {
		long n;
		if (value instanceof Integer || value instanceof Long) {
			n = ((Number) value).longValue();
		} else if (value instanceof BigInteger) {
			// 매우 큰 정수는 범위 밖이다.
			errors.add(field, ValidationReason.OUT_OF_RANGE);
			return null;
		} else {
			errors.add(field, ValidationReason.INVALID_FORMAT);
			return null;
		}
		if (n < MIN || n > MAX) {
			errors.add(field, ValidationReason.OUT_OF_RANGE);
			return null;
		}
		return (int) n;
	}
}
