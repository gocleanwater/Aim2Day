package com.aim2day.goal.progress.dto;

import com.aim2day.goal.common.time.UtcTime;
import com.aim2day.goal.progress.GoalProgressLog;
import com.aim2day.goal.progress.ProgressChangeType;
import com.aim2day.goal.progress.ProgressTargetType;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** 진행 기록 한 건(API 명세 §4.8). targetTitle은 목표 테이블에서 읽으며 삭제된 목표면 삭제 전 제목이다. */
public record ProgressLogResponse(long id, ProgressTargetType targetType, long targetId, String targetTitle,
		BigDecimal previousRate, BigDecimal newRate, ProgressChangeType changeType, LocalDate logDate, String memo,
		Instant createdAt) {

	public static ProgressLogResponse of(GoalProgressLog log, String targetTitle) {
		return new ProgressLogResponse(log.getId(), log.getTargetType(), log.getTargetId(), targetTitle,
				log.getPreviousRate(), log.getNewRate(), log.getChangeType(), log.getLogDate(), log.getMemo(),
				UtcTime.toInstant(log.getCreatedAt()));
	}
}
