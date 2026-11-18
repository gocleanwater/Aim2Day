package com.aim2day.goal.actions;

import com.aim2day.goal.actions.dto.ActionCompletionResponse;
import com.aim2day.goal.actions.dto.ActionResponse;
import com.aim2day.goal.actions.dto.CompletionRequest;
import com.aim2day.goal.actions.dto.CreateActionRequest;
import com.aim2day.goal.actions.dto.UpdateActionRequest;
import com.aim2day.goal.common.exception.BusinessException;
import com.aim2day.goal.common.exception.CommonErrorCode;
import com.aim2day.goal.common.exception.FieldErrors;
import com.aim2day.goal.common.exception.ValidationReason;
import com.aim2day.goal.common.exception.VersionGuard;
import com.aim2day.goal.common.lock.UserRowLock;
import com.aim2day.goal.common.time.UtcTime;
import com.aim2day.goal.goals.DecimalRules;
import com.aim2day.goal.goals.GoalErrorCode;
import com.aim2day.goal.goals.ProgressView;
import com.aim2day.goal.goals.monthly.MonthlyGoal;
import com.aim2day.goal.goals.monthly.MonthlyGoalRepository;
import com.aim2day.goal.goals.weekly.MeasurementType;
import com.aim2day.goal.goals.weekly.WeeklyGoal;
import com.aim2day.goal.goals.weekly.WeeklyGoalRepository;
import com.aim2day.goal.goals.weekly.record.NumericRecord;
import com.aim2day.goal.goals.weekly.record.NumericRecordRepository;
import com.aim2day.goal.progress.ActionCounts;
import com.aim2day.goal.progress.ProgressEngine;
import com.aim2day.goal.progress.WeeklyActionCounter;
import com.aim2day.goal.settings.SettingsService;
import com.aim2day.goal.settings.UserTime;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 행동 생성·수정·완료·삭제(API 명세 §4.9).
 *
 * 잠금 순서(바꾸지 않는다): users 행 -> 행동 행 -> 주간 목표(id 오름차순) -> 월간 목표(id 오름차순).
 * - 진행 기록(goal_progress_logs)을 남길 수 있는 모든 쓰기가 users 행을 트랜잭션 첫 쿼리로 잠근다. 진행 기록 INSERT가
 *   users 행에 공유 잠금을 거는 FK 확인을 하므로, 다른 경로(연간 생성·기준 저장·주간 수정)와 순서를 맞춰야 교착이 없다.
 *   덕분에 같은 사용자의 행동 쓰기가 한 줄로 서서 ProgressEngine이 앞선 요청의 커밋을 본다.
 *   진행 기록을 남기지 않는 MAINTENANCE 행동도 같은 순서를 쓴다(행동 행을 읽기 전에는 type을 알 수 없다).
 * - 행동 행은 FOR UPDATE로 잠근 뒤 상태를 다시 읽는다. 같은 행동을 두 인스턴스가 동시에 완료해도 먼저 잠근 쪽만
 *   상태를 바꾸고, 나중 쪽은 이미 같은 상태임을 보고 아무것도 하지 않는다. weekly_goal_numeric_records.action_id UNIQUE는
 *   그래도 새는 경우를 위한 이중 안전망이다.
 * - 주간 목표 두 개(이동)와 월간 목표 두 개는 id 오름차순으로 잠근다. 서로 반대 순서로 잠그는 두 요청이 교착하지 않게 한다.
 *
 * 응답은 쓰기 트랜잭션 안에서 flush·clear 뒤 다시 읽어 완성한다(직접 UPDATE가 바꾼 값을 반영하고, 커밋 뒤 별도
 * 트랜잭션에서 읽다가 그 사이 다른 요청이 바꾼 값이나 404를 만나는 경합을 없애기 위해서다).
 */
@Service
public class ActionService {

	private static final int TITLE_MAX = 255;
	private static final int SUMMARY_MAX = 2000;
	private static final int MEMO_MAX = 255;

	private final ActionRepository actions;
	private final WeeklyGoalRepository weeklies;
	private final MonthlyGoalRepository monthlies;
	private final NumericRecordRepository records;
	private final ActionAssembler assembler;
	private final ProgressEngine engine;
	private final WeeklyActionCounter counter;
	private final SettingsService settingsService;
	private final UserRowLock userRowLock;
	private final Clock clock;
	@PersistenceContext
	private EntityManager em;

	private final TransactionTemplate tx;
	private final TransactionTemplate newTx;

	public ActionService(ActionRepository actions, WeeklyGoalRepository weeklies, MonthlyGoalRepository monthlies,
			NumericRecordRepository records, ActionAssembler assembler, ProgressEngine engine,
			WeeklyActionCounter counter, SettingsService settingsService, UserRowLock userRowLock, Clock clock,
			PlatformTransactionManager txManager) {
		this.actions = actions;
		this.weeklies = weeklies;
		this.monthlies = monthlies;
		this.records = records;
		this.assembler = assembler;
		this.engine = engine;
		this.counter = counter;
		this.settingsService = settingsService;
		this.userRowLock = userRowLock;
		this.clock = clock;
		this.tx = new TransactionTemplate(txManager);
		this.newTx = new TransactionTemplate(txManager);
		this.newTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
	}

	/** 없음·타인 소유·삭제·경로 삭제를 모두 같은 404로 돌려 남의 행동이 존재하는지 알 수 없게 한다. */
	public static BusinessException notFound(long actionId) {
		return new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND, Map.of("resource", "ACTION", "id", actionId));
	}

	private static BusinessException parentUnavailable(String level, long id) {
		return new BusinessException(GoalErrorCode.GOAL_PARENT_UNAVAILABLE, Map.of("parentLevel", level, "parentId", id));
	}

	// ---------------------------------------------------------------- 생성

	public ActionResponse create(long userId, CreateActionRequest req) {
		// "오늘"과 "이번 주"는 생성 시점의 계정 설정으로 계산한다. 트랜잭션 밖에서 먼저 읽는다.
		UserTime time = settingsService.userTime(userId);

		// 1. 입력 검증(400)
		FieldErrors errors = new FieldErrors();
		List<String> presentLinks = new ArrayList<>();
		if (req.monthlyGoalIdPresent()) {
			presentLinks.add("monthlyGoalId");
		}
		if (req.weeklyGoalIdPresent()) {
			presentLinks.add("weeklyGoalId");
		}
		NewAction draft = validateNew(req.type(), req.title(), req.contributionValue(), req.contributionValuePresent(),
				presentLinks, req.monthlyGoalId() != null || req.weeklyGoalId() != null, "weeklyGoalId",
				req.monthlyGoalId(), req.weeklyGoalId(), req.performedDate(), req.dueDate(), time, errors);
		errors.throwIfAny();

		// 2. 현재 주 이후 날짜는 연결할 주간 목표가 있을 수 없다(주간 목표는 현재 주로만 만든다, 결정 5·17).
		requireWithinCurrentWeek(draft, time);

		return tx.execute(status -> {
			// 첫 쿼리는 users 행 잠금이다(클래스 설명).
			userRowLock.lock(userId);
			long actionId = insertInTx(userId, time, draft, "monthlyGoalId");
			return freshView(actionId, time.today());
		});
	}

	/** 검증을 통과한 행동 생성 입력. performedDate는 생략 시 오늘로 채워져 있고, title은 공백이 제거돼 있다. */
	public record NewAction(ActionType type, Long monthlyGoalId, Long weeklyGoalId, String title,
			LocalDate performedDate, LocalDate dueDate, BigDecimal contribution) {
	}

	/**
	 * 행동 생성 입력 규칙(§4.9 POST). 개별 생성과 온보딩 일괄 저장이 같은 규칙을 쓰도록 한곳에 둔다.
	 * 연결 대상을 가리키는 필드 이름이 요청 형식마다 다르므로(온보딩은 ...TempId도 쓴다) 호출자가 "보낸 연결 필드 이름"
	 * 목록과 "연결 대상이 하나라도 있는지"를 넘긴다. 보낸 것 자체가 잘못이다(MAINTENANCE)인 경우에 키가 있으면
	 * 값이 null이어도 보낸 것으로 센다(명시적 null도 "보낸 것"이다).
	 */
	public static NewAction validateNew(ActionType type, String rawTitle, BigDecimal rawContribution,
			boolean contributionPresent, List<String> presentLinkFields, boolean hasLink, String requiredLinkField,
			Long monthlyGoalId, Long weeklyGoalId, LocalDate performedDate, LocalDate dueDate, UserTime time,
			FieldErrors errors) {
		if (type == null) {
			errors.add("type", ValidationReason.REQUIRED);
		}
		String title = title(rawTitle, errors);
		BigDecimal contribution = null;
		if (rawContribution != null) {
			contribution = DecimalRules.positive(rawContribution, DecimalRules.MAX_VALUE, "contributionValue", errors);
		}
		if (type == ActionType.MAINTENANCE) {
			// 그 밖의 할 일은 목표 연결·기여량을 가질 수 없다. 보내는 것 자체가 잘못이다.
			for (String field : presentLinkFields) {
				errors.add(field, ValidationReason.INVALID_FORMAT);
			}
			if (contributionPresent) {
				errors.add("contributionValue", ValidationReason.INVALID_FORMAT);
			}
		} else if (type == ActionType.GOAL && !hasLink) {
			// 목표 없는 핵심 행동은 만들 수 없다(FOCUS-009).
			errors.add(requiredLinkField, ValidationReason.REQUIRED);
		}
		return new NewAction(type, monthlyGoalId, weeklyGoalId, title,
				performedDate != null ? performedDate : time.today(), dueDate, contribution);
	}

	/** 현재 주 이후 날짜의 GOAL 행동은 만들 수 없다(422). DB를 읽지 않으므로 트랜잭션 밖에서 불러도 된다. */
	public static void requireWithinCurrentWeek(NewAction action, UserTime time) {
		if (action.type() == ActionType.GOAL && action.performedDate().isAfter(time.currentWeek().endDate())) {
			throw outOfRange(time.currentWeek().endDate(), action.performedDate());
		}
	}

	/**
	 * 연결 대상 결정·부모 확인·INSERT·체크형 진행률 재계산. 호출자가 연 쓰기 트랜잭션 안에서, users 행 잠금을 건 뒤에
	 * 부른다. 새 행동의 id를 돌려준다. monthlyGoalField는 월간·주간 불일치 400의 필드 경로다.
	 */
	public long insertInTx(long userId, UserTime time, NewAction action, String monthlyGoalField) {
		boolean goal = action.type() == ActionType.GOAL;
		Long weeklyGoalId = null;
		boolean checklist = false;
		if (goal) {
			// 3~4. 연결 대상 결정과 부모 확인. 찾은 주간 목표는 행 잠금까지 잡아서 돌려준다.
			WeeklyGoal weekly = resolveWeekly(userId, action, monthlyGoalField);
			checklist = weekly.getMeasurementType() != MeasurementType.NUMERIC;
			// 5. 기여량은 수치형에만 줄 수 있다.
			if (checklist && action.contribution() != null) {
				throw new BusinessException(GoalErrorCode.GOAL_NOT_NUMERIC);
			}
			weeklyGoalId = weekly.getId();
		}
		Long actionId = actions.saveAndFlush(new Action(userId, weeklyGoalId, action.title(),
				action.performedDate(), action.dueDate(), action.contribution())).getId();
		if (checklist) {
			// 체크형은 연결 행동 수가 분모라 새 행동이 0%로 들어가 주간·월간 진행률이 바뀐다.
			engine.recalcWeekly(userId, weeklyGoalId, time.today(), "행동 추가: " + action.title());
		}
		return actionId;
	}

	/**
	 * GOAL 행동의 연결 주간 목표를 정하고 행 잠금까지 잡는다(규칙은 API 명세 §4.9 POST).
	 * 주간 목표를 직접 지정하면 기간 포함은 묻지 않는다. monthlyGoalId만 오면 수행일을 포함하는 활성 주간 목표를 찾는다.
	 */
	private WeeklyGoal resolveWeekly(long userId, NewAction req, String monthlyGoalField) {
		LocalDate performedDate = req.performedDate();
		if (req.weeklyGoalId() != null) {
			// 가시성 확인과 행 잠금을 한 번에 한다(INSERT의 FK 확인이 공유 잠금을 걸기 전에 배타 잠금을 먼저 잡는다).
			WeeklyGoal weekly = weeklies.findVisibleForUpdate(req.weeklyGoalId(), userId)
					.orElseThrow(() -> parentUnavailable("WEEKLY", req.weeklyGoalId()));
			if (req.monthlyGoalId() != null && !req.monthlyGoalId().equals(weekly.getMonthlyGoalId())) {
				// 함께 보낸 월간 목표가 그 주간 목표의 부모가 아니다. 요청이 서로 모순이므로 400이다.
				throw new FieldErrors().add(monthlyGoalField, ValidationReason.INVALID_FORMAT).toException();
			}
			return weekly;
		}
		long monthlyGoalId = req.monthlyGoalId();
		monthlies.findVisible(monthlyGoalId, userId).orElseThrow(() -> parentUnavailable("MONTHLY", monthlyGoalId));
		List<WeeklyGoal> candidates = weeklies.findActiveByMonthlyContaining(monthlyGoalId, performedDate);
		if (candidates.isEmpty()) {
			// 없는 주간 목표를 새로 만들어 주지 않는다(지난 주도 마찬가지).
			throw new BusinessException(ActionErrorCode.ACTION_WEEKLY_GOAL_REQUIRED,
					Map.of("monthlyGoalId", monthlyGoalId, "performedDate", performedDate.toString()));
		}
		if (candidates.size() > 1) {
			List<Map<String, Object>> list = new ArrayList<>();
			for (WeeklyGoal c : candidates) {
				list.add(Map.of("id", c.getId(), "title", c.getTitle(), "startDate", c.getStartDate().toString(),
						"endDate", c.getEndDate().toString()));
			}
			throw new BusinessException(ActionErrorCode.ACTION_WEEKLY_GOAL_AMBIGUOUS, Map.of("candidates", list));
		}
		long weeklyGoalId = candidates.get(0).getId();
		return weeklies.findVisibleForUpdate(weeklyGoalId, userId)
				.orElseThrow(() -> parentUnavailable("WEEKLY", weeklyGoalId));
	}

	// ---------------------------------------------------------------- 완료

	public ActionCompletionResponse complete(long userId, long actionId, CompletionRequest req) {
		UserTime time = settingsService.userTime(userId);
		FieldErrors errors = new FieldErrors();
		if (req.completed() == null) {
			errors.add("completed", ValidationReason.REQUIRED);
		}
		if (req.memo() != null && req.memo().codePointCount(0, req.memo().length()) > MEMO_MAX) {
			errors.add("memo", ValidationReason.TOO_LONG);
		}
		errors.throwIfAny();
		boolean completed = req.completed();

		return tx.execute(status -> {
			userRowLock.lock(userId);
			// 행동 행 잠금 뒤 상태를 다시 읽는다. 같은 행동의 동시 요청 중 하나만 아래 변경을 하고, 나머지는 같은 상태를 본다.
			Action action = actions.findWritableForUpdate(actionId, userId)
					.orElseThrow(() -> notFound(actionId));
			if (action.isCompleted() == completed) {
				// 이미 같은 상태: 아무것도 바꾸지 않고 현재 상태를 돌려준다. 미래 수행일 검사도 하지 않는다.
				return completionView(actionId, time.today());
			}
			if (completed && action.getPerformedDate().isAfter(time.today())) {
				// 아직 하지 않은 일이 리포트에 집계되지 않게 한다. 완료 취소는 언제든 할 수 있다.
				throw new BusinessException(ActionErrorCode.ACTION_FUTURE_COMPLETION,
						Map.of("today", time.today().toString(), "performedDate",
								action.getPerformedDate().toString()));
			}
			Long weeklyGoalId = action.getWeeklyGoalId();
			if (weeklyGoalId == null) {
				// MAINTENANCE는 자신의 완료 상태만 바꾼다(FOCUS-006, FOCUS-011).
				actions.updateCompletion(actionId, completed, completed ? UtcTime.now(clock) : null);
				return completionView(actionId, time.today());
			}
			WeeklyGoal weekly = weeklies.findVisibleForUpdate(weeklyGoalId, userId)
					.orElseThrow(() -> notFound(actionId));
			if (weekly.getMeasurementType() == MeasurementType.NUMERIC) {
				applyNumericCompletion(action, weekly, completed);
			}
			actions.updateCompletion(actionId, completed, completed ? UtcTime.now(clock) : null);
			String memo = req.memo() != null ? req.memo()
					: (completed ? "행동 완료: " : "행동 완료 취소: ") + action.getTitle();
			// 체크형은 분자가 바뀌고, 수치형은 방금 더한(뺀) current_value로 진행률을 다시 계산한다. 월간도 이어서 계산된다.
			engine.recalcWeekly(userId, weeklyGoalId, time.today(), memo);
			return completionView(actionId, time.today());
		});
	}

	/**
	 * 수치형 주간 목표의 기여 기록을 맞춘다. current_value는 Java에서 읽어 더하지 않고 DB가 원자적으로 더한다
	 * (서로 다른 행동의 동시 완료에서 기여량이 사라지지 않게, ERD 결정 16). 취소는 기록에 저장된 amount를 뺀다.
	 */
	private void applyNumericCompletion(Action action, WeeklyGoal weekly, boolean completed) {
		long weeklyGoalId = weekly.getId();
		if (completed) {
			BigDecimal amount = action.getContributionValue();
			if (amount == null) {
				return;
			}
			requireRoom(weekly.getCurrentValue(), amount);
			records.saveAndFlush(NumericRecord.actionCompletion(weeklyGoalId, action.getId(), amount));
			engine.addNumeric(weeklyGoalId, amount);
		} else {
			Optional<NumericRecord> record = records.findByActionId(action.getId());
			if (record.isPresent()) {
				BigDecimal amount = record.get().getAmount();
				records.delete(record.get());
				records.flush();
				engine.addNumeric(weeklyGoalId, amount.negate());
			}
		}
	}

	/** current_value가 DECIMAL(10,2) 상한을 넘으면 UPDATE가 DB 오류(500)가 되므로 잠근 행의 값으로 미리 400으로 막는다. */
	private static void requireRoom(BigDecimal current, BigDecimal add) {
		if (current.add(add).compareTo(DecimalRules.MAX_VALUE) > 0) {
			throw new FieldErrors().add("contributionValue", ValidationReason.OUT_OF_RANGE).toException();
		}
	}

	/**
	 * 완료 응답. 직접 UPDATE 뒤에는 영속성 컨텍스트에 낡은 엔티티가 남아 있으므로 DB로 내보낸 뒤 비우고 다시 읽는다.
	 * 이 트랜잭션이 잠근 행(행동·주간·월간)의 값이라 다른 요청이 끼어들 수 없다.
	 */
	private ActionCompletionResponse completionView(long actionId, LocalDate today) {
		em.flush();
		em.clear();
		Action action = actions.findById(actionId).orElseThrow();
		ActionResponse view = assembler.responses(List.of(action), today).get(0);
		if (action.getWeeklyGoalId() == null) {
			return new ActionCompletionResponse(view, null, null);
		}
		WeeklyGoal weekly = weeklies.findById(action.getWeeklyGoalId()).orElseThrow();
		MonthlyGoal monthly = monthlies.findById(weekly.getMonthlyGoalId()).orElseThrow();
		boolean numeric = weekly.getMeasurementType() == MeasurementType.NUMERIC;
		ActionCounts counts = counter.counts(weekly.getId());
		// 수치형은 목표값이 있으므로 0%도 기록이다. 체크형은 분모가 0이면 기록 없음이다(주간 목표 응답과 같은 규칙).
		ProgressView weeklyProgress = numeric || counts.total() > 0 ? ProgressView.recorded(weekly.getProgressRate())
				: ProgressView.noRecord();
		int weeklyCount = weeklies.countActiveByMonthlyGoals(List.of(monthly.getId())).stream()
				.mapToInt(row -> ((Number) row[1]).intValue()).sum();
		ProgressView monthlyProgress = weeklyCount > 0 ? ProgressView.recorded(monthly.getProgressRate())
				: ProgressView.noRecord();
		return new ActionCompletionResponse(view,
				new ActionCompletionResponse.WeeklyView(weekly.getId(), weeklyProgress,
						numeric ? weekly.getCurrentValue() : null,
						numeric ? weekly.getCurrentValue().compareTo(weekly.getTargetValue()) >= 0 : null),
				new ActionCompletionResponse.MonthlyView(monthly.getId(), monthlyProgress));
	}

	// ---------------------------------------------------------------- 수정

	public ActionResponse update(long userId, long actionId, UpdateActionRequest req) {
		UserTime time = settingsService.userTime(userId);
		// 사전 비교를 통과한 뒤 다른 인스턴스가 먼저 커밋하는 경합은 @Version UPDATE가 막고, 트랜잭션 바깥에서 409로
		// 바꾼다(행 잠금을 쥐고 있어 실제로는 드물다). 쓰기 트랜잭션에는 격리 수준을 지정하지 않는다
		// (H2의 REPEATABLE READ는 낙관적 락 대신 H2 전용 예외를 낸다).
		return VersionGuard.translateConflict(() -> tx.execute(status -> {
			userRowLock.lock(userId);
			Action action = actions.findWritableForUpdate(actionId, userId)
					.orElseThrow(() -> notFound(actionId));
			boolean goal = action.getType() == ActionType.GOAL;
			boolean completed = action.isCompleted();

			// ---- 필드 검증 (없음 = 유지, JSON null = 지움 또는 400)
			FieldErrors errors = new FieldErrors();
			String title = action.getTitle();
			if (req.title() != null) {
				if (req.title().isPresent()) {
					title = title(req.title().get(), errors);
				} else {
					errors.add("title", ValidationReason.REQUIRED);
				}
			}
			LocalDate performedDate = action.getPerformedDate();
			if (req.performedDate() != null) {
				if (req.performedDate().isPresent()) {
					performedDate = req.performedDate().get();
				} else {
					errors.add("performedDate", ValidationReason.REQUIRED);
				}
			}
			LocalDate dueDate = req.dueDate() != null ? req.dueDate().orElse(null) : action.getDueDate();
			String resultSummary = action.getResultSummary();
			if (req.resultSummary() != null) {
				resultSummary = req.resultSummary().orElse(null);
				if (resultSummary != null && resultSummary.codePointCount(0, resultSummary.length()) > SUMMARY_MAX) {
					errors.add("resultSummary", ValidationReason.TOO_LONG);
				}
			}
			boolean contributionGiven = req.contributionValue() != null;
			BigDecimal newContribution = action.getContributionValue();
			if (contributionGiven) {
				if (req.contributionValue().isEmpty()) {
					newContribution = null;
				} else if (goal) {
					BigDecimal checked = DecimalRules.positive(req.contributionValue().get(),
							DecimalRules.MAX_VALUE, "contributionValue", errors);
					newContribution = checked != null ? checked : newContribution;
				} else {
					// 그 밖의 할 일에는 기여량을 줄 수 없다.
					errors.add("contributionValue", ValidationReason.INVALID_FORMAT);
				}
			}
			if (req.type() != null) {
				if (req.type().isEmpty()) {
					errors.add("type", ValidationReason.REQUIRED);
				} else if (req.type().get() != action.getType()) {
					// 목표 연결 <-> 그 밖의 할 일 전환은 지원하지 않는다.
					errors.add("type", ValidationReason.INVALID_FORMAT);
				}
			}
			long oldWeeklyId = goal ? action.getWeeklyGoalId() : 0L;
			long newWeeklyId = oldWeeklyId;
			if (req.weeklyGoalId() != null) {
				if (!goal) {
					if (req.weeklyGoalId().isPresent()) {
						errors.add("weeklyGoalId", ValidationReason.INVALID_FORMAT);
					}
				} else if (req.weeklyGoalId().isEmpty()) {
					errors.add("weeklyGoalId", ValidationReason.REQUIRED);
				} else {
					newWeeklyId = req.weeklyGoalId().get();
				}
			}
			errors.throwIfAny();

			VersionGuard.check(req.version(), action.getVersion());

			// ---- 수행일 규칙(바뀔 때만 검사한다)
			LocalDate today = time.today();
			if (!performedDate.equals(action.getPerformedDate())) {
				if (completed && performedDate.isAfter(today)) {
					throw new BusinessException(ActionErrorCode.ACTION_FUTURE_COMPLETION,
							Map.of("today", today.toString(), "performedDate", performedDate.toString()));
				}
				// 지난 주 연결을 유지한 채 오늘로 옮기는 것은 허용한다. 막는 것은 현재 주 끝보다 뒤로 가는 경우뿐이다.
				if (goal && performedDate.isAfter(time.currentWeek().endDate())) {
					throw outOfRange(time.currentWeek().endDate(), performedDate);
				}
			}

			boolean affectsProgress = false;
			if (goal) {
				boolean moved = newWeeklyId != oldWeeklyId;
				WeeklyGoal target = lockWeeklies(userId, actionId, oldWeeklyId, newWeeklyId, moved);
				boolean targetNumeric = target.getMeasurementType() == MeasurementType.NUMERIC;
				// 체크형 주간 목표에는 기여량이 있을 수 없다. 이동할 때 기여량이 있던 행동은 같은 요청에서
				// null을 보내 버리는 것을 확인해야 한다(조용히 버리지 않는다).
				if (!targetNumeric && newContribution != null) {
					throw new BusinessException(GoalErrorCode.GOAL_NOT_NUMERIC);
				}

				// 완료된 행동의 기여 기록과 새 기여량
				NumericRecord record = completed ? records.findByActionId(actionId).orElse(null) : null;
				BigDecimal oldAmount = record != null ? record.getAmount() : null;
				BigDecimal newAmount = completed && targetNumeric ? newContribution : null;
				boolean amountChanged = moved ? (oldAmount != null || newAmount != null)
						: !equalAmount(oldAmount, newAmount);
				affectsProgress = moved || amountChanged;

				if (newAmount != null && amountChanged) {
					// 이동하지 않으면 이전 기여량이 빠진 값 기준으로 상한을 본다. target은 방금 잠근 행이라 최신 값이다.
					BigDecimal base = !moved && oldAmount != null ? target.getCurrentValue().subtract(oldAmount)
							: target.getCurrentValue();
					requireRoom(base, newAmount);
				}
				if (moved) {
					// 월간 목표도 id 오름차순으로 미리 잠근다. 이전·새 경로의 재계산이 서로 반대 순서로 잠그지 않게 한다.
					lockMonthliesInOrder(oldWeeklyId, newWeeklyId);
				}
				if (amountChanged) {
					applyAmountChange(actionId, record, oldAmount, newAmount, oldWeeklyId, newWeeklyId, moved);
				}
			}

			action.change(title, performedDate, dueDate, resultSummary, goal ? newWeeklyId : null, newContribution);
			actions.saveAndFlush(action);

			if (affectsProgress) {
				String memo = "행동 수정: " + title;
				// 이전·새 주간을 모두 저장한 뒤에 월간을 계산한다. 같은 월간 아래 이동이면 월간을 한 번만, 두 주간의
				// 최종값으로 계산해야 가짜 상승·하락 진행 기록이 남지 않는다. 월간은 id 오름차순(이미 그 순서로 잠겨 있다).
				TreeSet<Long> monthlyIds = new TreeSet<>();
				monthlyIds.add(engine.recalcWeeklyOnly(userId, newWeeklyId, today, memo).monthlyGoalId());
				if (newWeeklyId != oldWeeklyId) {
					monthlyIds.add(engine.recalcWeeklyOnly(userId, oldWeeklyId, today, memo).monthlyGoalId());
				}
				for (long monthlyId : monthlyIds) {
					engine.recalcMonthly(userId, monthlyId, today, memo);
				}
			}
			return freshView(actionId, today);
		}), () -> currentVersion(actionId));
	}

	/**
	 * 행동이 연결된 주간 목표를 잠그고 현재(이동이면 새) 주간 목표를 돌려준다. 이동이면 이전·새 주간 목표를
	 * id 오름차순으로 잠근다. 새 주간 목표는 잠그면서 가시성도 다시 확인한다(잠그기 전의 확인은 그 사이 삭제를 못 잡는다).
	 */
	private WeeklyGoal lockWeeklies(long userId, long actionId, long oldWeeklyId, long newWeeklyId, boolean moved) {
		if (!moved) {
			return weeklies.findVisibleForUpdate(oldWeeklyId, userId).orElseThrow(() -> notFound(actionId));
		}
		WeeklyGoal target = null;
		for (long id : oldWeeklyId < newWeeklyId ? new long[] {oldWeeklyId, newWeeklyId}
				: new long[] {newWeeklyId, oldWeeklyId}) {
			if (id == newWeeklyId) {
				target = weeklies.findVisibleForUpdate(id, userId)
						.orElseThrow(() -> parentUnavailable("WEEKLY", newWeeklyId));
			} else {
				engine.lockWeekly(id);
			}
		}
		return target;
	}

	/** 이전·새 주간 목표의 월간 목표를 id 오름차순으로 잠근다(같으면 한 번). */
	private void lockMonthliesInOrder(long oldWeeklyId, long newWeeklyId) {
		long oldMonthly = weeklies.findById(oldWeeklyId).orElseThrow().getMonthlyGoalId();
		long newMonthly = weeklies.findById(newWeeklyId).orElseThrow().getMonthlyGoalId();
		for (long id : new TreeSet<>(List.of(oldMonthly, newMonthly))) {
			engine.lockMonthly(id);
		}
	}

	/**
	 * 완료된 행동의 기여량·주간 목표 변경을 수치 기록과 current_value에 반영한다(ERD 8번 규칙).
	 * 같은 주간 목표면 (새 - 이전) 차이만 한 번 더하고, 이동이면 이전에서 빼고 새에서 더한다.
	 * 기록 행은 가능하면 그대로 두고 amount·weekly_goal_id만 고친다(id와 생성 시각 유지).
	 */
	private void applyAmountChange(long actionId, NumericRecord record, BigDecimal oldAmount, BigDecimal newAmount,
			long oldWeeklyId, long newWeeklyId, boolean moved) {
		if (moved) {
			if (oldAmount != null) {
				engine.addNumeric(oldWeeklyId, oldAmount.negate());
			}
			if (newAmount != null) {
				engine.addNumeric(newWeeklyId, newAmount);
			}
		} else {
			BigDecimal delta = nz(newAmount).subtract(nz(oldAmount));
			if (delta.signum() != 0) {
				engine.addNumeric(newWeeklyId, delta);
			}
		}
		if (record != null && newAmount != null) {
			records.updateWeeklyAndAmount(record.getId(), newWeeklyId, newAmount);
		} else if (record != null) {
			records.delete(record);
			records.flush();
		} else if (newAmount != null) {
			records.saveAndFlush(NumericRecord.actionCompletion(newWeeklyId, actionId, newAmount));
		}
	}

	private static boolean equalAmount(BigDecimal a, BigDecimal b) {
		return a == null ? b == null : b != null && a.compareTo(b) == 0;
	}

	private static BigDecimal nz(BigDecimal v) {
		return v == null ? BigDecimal.ZERO : v;
	}

	/** 롤백이 끝난 뒤 새 트랜잭션에서 최신 version을 읽는다. 실패한 트랜잭션의 영속성 컨텍스트는 오염돼 있다. */
	private long currentVersion(long actionId) {
		Long version = newTx.execute(status -> actions.findById(actionId).map(Action::getVersion).orElse(null));
		if (version == null) {
			throw notFound(actionId);
		}
		return version;
	}

	// ---------------------------------------------------------------- 삭제

	public void delete(long userId, long actionId) {
		UserTime time = settingsService.userTime(userId);
		tx.execute(status -> {
			userRowLock.lock(userId);
			Action action = actions.findWritableForUpdate(actionId, userId)
					.orElseThrow(() -> notFound(actionId));
			actions.softDelete(actionId, UtcTime.now(clock));
			if (!action.isCompleted() && action.getWeeklyGoalId() != null) {
				// 삭제한 미완료 행동은 체크형 분모에서 빠지므로 진행률을 다시 계산한다.
				// 완료 행동은 목록에서만 숨기고 기록·분자·분모는 그대로 남기므로(ERD 4.4) 재계산이 필요 없다.
				engine.recalcWeekly(userId, action.getWeeklyGoalId(), time.today(), "행동 삭제: " + action.getTitle());
			}
			return null;
		});
	}

	// ---------------------------------------------------------------- 공통

	/** 직접 UPDATE 뒤에는 영속성 컨텍스트가 낡았으므로 내보낸 뒤 비우고 다시 읽어 응답을 만든다. */
	private ActionResponse freshView(long actionId, LocalDate today) {
		em.flush();
		em.clear();
		return assembler.responses(List.of(actions.findById(actionId).orElseThrow()), today).get(0);
	}

	private static String title(String raw, FieldErrors errors) {
		String title = raw == null ? "" : raw.strip();
		if (title.isEmpty()) {
			errors.add("title", ValidationReason.REQUIRED);
		} else if (title.codePointCount(0, title.length()) > TITLE_MAX) {
			errors.add("title", ValidationReason.TOO_LONG);
		}
		return title;
	}

	private static BusinessException outOfRange(LocalDate currentWeekEnd, LocalDate performedDate) {
		return new BusinessException(ActionErrorCode.ACTION_DATE_OUT_OF_RANGE,
				Map.of("currentWeekEndDate", currentWeekEnd.toString(), "performedDate", performedDate.toString()));
	}
}
