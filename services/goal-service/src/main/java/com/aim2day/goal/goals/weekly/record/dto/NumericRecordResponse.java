package com.aim2day.goal.goals.weekly.record.dto;

import com.aim2day.goal.common.time.UtcTime;
import com.aim2day.goal.goals.weekly.record.NumericRecord;
import com.aim2day.goal.goals.weekly.record.RecordSource;
import java.math.BigDecimal;
import java.time.Instant;

/** 수치 기록 한 건(API 명세 §4.6). 직접 입력이면 action이 null이다. */
public record NumericRecordResponse(long id, RecordSource source, ActionRef action, BigDecimal amount, String memo,
		Instant createdAt) {

	/** 행동 제목은 actions 테이블이 생기는 단위 4에서 채운다. 지금은 id만 알 수 있어 title이 null이다. */
	public record ActionRef(long id, String title) {
	}

	public static NumericRecordResponse of(NumericRecord record) {
		ActionRef action = record.getActionId() == null ? null : new ActionRef(record.getActionId(), null);
		return new NumericRecordResponse(record.getId(), record.getSource(), action, record.getAmount(),
				record.getMemo(), UtcTime.toInstant(record.getCreatedAt()));
	}
}
