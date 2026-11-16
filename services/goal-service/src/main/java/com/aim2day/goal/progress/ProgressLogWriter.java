package com.aim2day.goal.progress;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 진행 기록 쓰기. 호출자 트랜잭션 안에서 INSERT만 한다(MANDATORY).
 * 진행률 변경과 기록이 한 트랜잭션이어야 한쪽만 남는 일이 없다. 호출자가 롤백하면 기록도 함께 사라진다.
 * 단위 3·4의 주간·월간 진행률 변경도 이 메서드로 남긴다.
 */
@Component
public class ProgressLogWriter {

	private final ProgressLogRepository repository;

	public ProgressLogWriter(ProgressLogRepository repository) {
		this.repository = repository;
	}

	@Transactional(propagation = Propagation.MANDATORY)
	public GoalProgressLog record(long userId, ProgressTargetType type, long targetId, BigDecimal previousRate,
			BigDecimal newRate, ProgressChangeType changeType, LocalDate logDate, String memo) {
		// 컬럼이 DECIMAL(5,2)이므로 소수 둘째 자리(HALF_UP)로 맞춰 저장한다. 연간 달성률 20은 20.00이 된다.
		return repository.save(new GoalProgressLog(userId, type, targetId, scale(previousRate), scale(newRate),
				changeType, logDate, memo));
	}

	private static BigDecimal scale(BigDecimal rate) {
		return rate.setScale(2, RoundingMode.HALF_UP);
	}
}
