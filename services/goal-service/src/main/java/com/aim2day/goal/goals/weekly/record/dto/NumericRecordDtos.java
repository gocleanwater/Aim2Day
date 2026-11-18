package com.aim2day.goal.goals.weekly.record.dto;

import com.aim2day.goal.common.time.UtcTime;
import com.aim2day.goal.goals.weekly.dto.WeeklyGoalDtos.WeeklyGoalResponse;
import com.aim2day.goal.goals.weekly.record.NumericRecord;
import com.aim2day.goal.goals.weekly.record.RecordSource;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

/** 이 도메인의 요청·응답 형식 모음. */
public final class NumericRecordDtos {

	private NumericRecordDtos() {
	}

	/** amount는 누적값이 아니라 이번에 추가한 양이다(PROG-003). 검증은 서비스가 한다(소수 자릿수·범위). */
	public record CreateNumericRecordRequest(BigDecimal amount, String memo) {
	}

	/** 기록을 추가한 뒤의 기록과 갱신된 주간 목표(진행률·현재값 포함). */
	public record NumericRecordCreatedResponse(NumericRecordResponse record, WeeklyGoalResponse weeklyGoal) {
	}

	/** 명세에 응답 본문이 없어 추가 응답과 같은 키(weeklyGoal)로 차감·재계산된 주간 목표를 돌려준다. */
	public record NumericRecordDeletedResponse(WeeklyGoalResponse weeklyGoal) {
	}

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
}
