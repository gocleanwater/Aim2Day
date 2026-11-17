package com.aim2day.goal.goals.weekly.record.dto;

import com.aim2day.goal.common.time.UtcTime;
import com.aim2day.goal.goals.weekly.record.NumericRecord;
import com.aim2day.goal.goals.weekly.record.RecordSource;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

/** 수치 기록 한 건(API 명세 §4.6). 직접 입력이면 action이 null이다. */
public record NumericRecordResponse(long id, RecordSource source, ActionRef action, BigDecimal amount, String memo,
		Instant createdAt) {

	public record ActionRef(long id, String title) {
	}

	/** 직접 입력 기록용(action이 null이다). */
	public static NumericRecordResponse of(NumericRecord record) {
		return of(record, Map.of());
	}

	/** titles는 행동 id -> 제목. 행동 완료 기록이 가리키는 행동의 제목을 채운다(행동이 삭제돼도 기록은 남는다). */
	public static NumericRecordResponse of(NumericRecord record, Map<Long, String> titles) {
		ActionRef action = record.getActionId() == null ? null
				: new ActionRef(record.getActionId(), titles.get(record.getActionId()));
		return new NumericRecordResponse(record.getId(), record.getSource(), action, record.getAmount(),
				record.getMemo(), UtcTime.toInstant(record.getCreatedAt()));
	}
}
