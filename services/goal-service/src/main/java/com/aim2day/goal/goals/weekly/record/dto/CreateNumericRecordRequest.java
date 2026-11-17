package com.aim2day.goal.goals.weekly.record.dto;

import java.math.BigDecimal;

/** amount는 누적값이 아니라 이번에 추가한 양이다(PROG-003). 검증은 서비스가 한다(소수 자릿수·범위). */
public record CreateNumericRecordRequest(BigDecimal amount, String memo) {
}
