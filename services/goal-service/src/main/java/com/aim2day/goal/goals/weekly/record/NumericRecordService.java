package com.aim2day.goal.goals.weekly.record;

import com.aim2day.goal.actions.Action;
import com.aim2day.goal.actions.ActionRepository;
import com.aim2day.goal.common.exception.BusinessException;
import com.aim2day.goal.common.exception.CommonErrorCode;
import com.aim2day.goal.common.exception.FieldErrors;
import com.aim2day.goal.common.exception.ValidationReason;
import com.aim2day.goal.common.lock.UserRowLock;
import com.aim2day.goal.common.tx.Tx;
import com.aim2day.goal.common.validation.TextRules;
import com.aim2day.goal.goals.DecimalRules;
import com.aim2day.goal.goals.GoalAssembler;
import com.aim2day.goal.goals.GoalErrorCode;
import com.aim2day.goal.goals.weekly.MeasurementType;
import com.aim2day.goal.goals.weekly.WeeklyGoal;
import com.aim2day.goal.goals.weekly.WeeklyGoalRepository;
import com.aim2day.goal.goals.weekly.WeeklyGoalService;
import com.aim2day.goal.goals.weekly.dto.WeeklyGoalDtos.WeeklyGoalResponse;
import com.aim2day.goal.goals.weekly.record.dto.NumericRecordDtos.CreateNumericRecordRequest;
import com.aim2day.goal.goals.weekly.record.dto.NumericRecordDtos.NumericRecordCreatedResponse;
import com.aim2day.goal.goals.weekly.record.dto.NumericRecordDtos.NumericRecordDeletedResponse;
import com.aim2day.goal.goals.weekly.record.dto.NumericRecordDtos.NumericRecordResponse;
import com.aim2day.goal.progress.ProgressEngine;
import com.aim2day.goal.settings.SettingsService;
import com.aim2day.goal.settings.UserTime;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 수치형 주간 목표의 직접 기록 추가·삭제·조회.
 *
 * 쓰기 경로의 한 트랜잭션은 순서가 고정이다: users 행 잠금 -> 주간 목표 행 잠금 -> 기록 INSERT/DELETE ->
 * current_value 원자적 가감 -> 주간·월간 진행률 재계산(ProgressEngine). 기록과 current_value, 진행률이
 * 한 트랜잭션이라 중간에 실패하면 모두 롤백된다. users 행을 먼저 잠그는 이유는 WeeklyGoalService 클래스 설명과 같다
 * (진행 기록 INSERT가 users 행에 공유 잠금을 거는 FK 확인을 하므로, 다른 users 잠금 경로와 순서를 맞춘다).
 * 이 경로는 주간 목표의 version을 올리지 않는다(API 명세 §2.4). 기록을 남겼다고 열려 있는 목표 수정 화면이
 * 409가 되면 안 된다.
 */
@Service
public class NumericRecordService {

	private static final int MEMO_MAX = 255;

	private final NumericRecordRepository records;
	private final ActionRepository actionRepository;
	private final WeeklyGoalRepository weeklyRepository;
	private final WeeklyGoalService weeklyService;
	private final GoalAssembler assembler;
	private final ProgressEngine engine;
	private final SettingsService settingsService;
	private final UserRowLock userRowLock;
	@PersistenceContext
	private EntityManager em;

	private final TransactionTemplate tx;
	private final TransactionTemplate readTx;

	public NumericRecordService(NumericRecordRepository records, ActionRepository actionRepository,
			WeeklyGoalRepository weeklyRepository,
			WeeklyGoalService weeklyService, GoalAssembler assembler, ProgressEngine engine,
			SettingsService settingsService, UserRowLock userRowLock, PlatformTransactionManager txManager) {
		this.records = records;
		this.actionRepository = actionRepository;
		this.weeklyRepository = weeklyRepository;
		this.weeklyService = weeklyService;
		this.assembler = assembler;
		this.engine = engine;
		this.settingsService = settingsService;
		this.userRowLock = userRowLock;
		this.tx = Tx.write(txManager);
		this.readTx = Tx.readOnly(txManager);
	}

	public List<NumericRecordResponse> list(long userId, long weeklyGoalId) {
		return readTx.execute(status -> {
			weeklyService.findOwnedVisible(userId, weeklyGoalId);
			List<NumericRecord> found = records.findByWeeklyGoalIdOrderByCreatedAtDescIdDesc(weeklyGoalId);
			// 행동 완료 기록의 행동 제목을 한 번에 읽는다(삭제된 행동의 제목도 보여 준다).
			List<Long> actionIds = found.stream().map(NumericRecord::getActionId).filter(Objects::nonNull).toList();
			Map<Long, String> titles = new HashMap<>();
			for (Action a : actionRepository.findAllById(actionIds)) {
				titles.put(a.getId(), a.getTitle());
			}
			return found.stream().map(r -> NumericRecordResponse.of(r, titles)).toList();
		});
	}

	public NumericRecordCreatedResponse add(long userId, long weeklyGoalId, CreateNumericRecordRequest req) {
		UserTime time = settingsService.userTime(userId);

		FieldErrors errors = new FieldErrors();
		BigDecimal amount = DecimalRules.positive(req.amount(), DecimalRules.MAX_VALUE, "amount", errors);
		TextRules.maxLength(req.memo(), "memo", MEMO_MAX, errors);
		errors.throwIfAny();

		return tx.execute(status -> {
			userRowLock.lock(userId);
			WeeklyGoal goal = weeklyRepository.findVisibleForUpdate(weeklyGoalId, userId)
					.orElseThrow(() -> WeeklyGoalService.notFound(weeklyGoalId));
			if (goal.getMeasurementType() != MeasurementType.NUMERIC) {
				throw new BusinessException(GoalErrorCode.GOAL_NOT_NUMERIC);
			}
			// 상한 검사는 잠근 행에서 읽은 값으로 한다. 잠그기 전에 읽은 값은 다른 인스턴스의 기록을 놓칠 수 있다.
			// DECIMAL(10,2) 상한을 넘으면 UPDATE가 DB 오류(500)가 되므로 미리 400으로 막는다.
			if (goal.getCurrentValue().add(amount).compareTo(DecimalRules.MAX_VALUE) > 0) {
				throw new FieldErrors().add("amount", ValidationReason.OUT_OF_RANGE).toException();
			}
			NumericRecord saved = records.saveAndFlush(NumericRecord.directInput(weeklyGoalId, amount, req.memo()));
			engine.addNumeric(weeklyGoalId, amount);
			engine.recalcWeekly(userId, weeklyGoalId, time.today(), "수치 기록: +" + plain(amount));
			// 응답은 같은 쓰기 트랜잭션(잠금을 쥔 상태)에서 완성한다. 커밋 뒤 별도 트랜잭션에서 다시 읽으면 그 사이
			// 다른 탭이 기록을 삭제하거나 목표를 숨겨 "생성은 성공했는데 500/404"가 날 수 있다.
			NumericRecordResponse recordView = NumericRecordResponse.of(saved);
			return new NumericRecordCreatedResponse(recordView, weeklyViewFresh(userId, weeklyGoalId, time.today()));
		});
	}

	public NumericRecordDeletedResponse delete(long userId, long weeklyGoalId, long recordId) {
		UserTime time = settingsService.userTime(userId);
		WeeklyGoalResponse view = tx.execute(status -> {
			userRowLock.lock(userId);
			weeklyRepository.findVisibleForUpdate(weeklyGoalId, userId)
					.orElseThrow(() -> WeeklyGoalService.notFound(weeklyGoalId));
			// 기록이 이 주간 목표의 것인지 조회 조건에 넣는다. 다른 목표의 기록 id는 404다.
			NumericRecord record = records.findByIdAndWeeklyGoalId(recordId, weeklyGoalId)
					.orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND,
							Map.of("resource", "NUMERIC_RECORD", "id", recordId)));
			// 행동 완료로 생긴 기록은 행동의 완료 취소로만 지울 수 있다(둘이 어긋나면 완료 상태와 실적이 맞지 않는다).
			if (record.getSource() != RecordSource.DIRECT_INPUT) {
				throw new BusinessException(RecordErrorCode.RECORD_NOT_DELETABLE);
			}
			records.delete(record);
			records.flush();
			engine.addNumeric(weeklyGoalId, record.getAmount().negate());
			engine.recalcWeekly(userId, weeklyGoalId, time.today(), "수치 기록 삭제: -" + plain(record.getAmount()));
			// add()와 같은 이유로 응답을 쓰기 트랜잭션 안에서 만든다(커밋 뒤 주간 목표가 숨겨져도 삭제는 성공으로 응답).
			return weeklyViewFresh(userId, weeklyGoalId, time.today());
		});
		return new NumericRecordDeletedResponse(view);
	}

	/**
	 * 직접 UPDATE(addNumeric/recalc) 뒤 영속성 컨텍스트에는 낡은 엔티티가 남아 있다. 변경을 DB로 내보낸 뒤
	 * 컨텍스트를 비워 다시 읽어야 이번 기록이 반영된 current_value·진행률이 응답에 실린다.
	 */
	private WeeklyGoalResponse weeklyViewFresh(long userId, long weeklyGoalId, LocalDate today) {
		em.flush();
		em.clear();
		return assembler.weeklyResponses(List.of(weeklyService.findOwnedVisible(userId, weeklyGoalId)), today).get(0);
	}

	private static String plain(BigDecimal amount) {
		return amount.stripTrailingZeros().toPlainString();
	}
}
