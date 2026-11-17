package com.aim2day.goal.progress;

import java.math.BigDecimal;

/** 월간 진행률 가중 평균의 입력 한 건: 하위 주간 목표의 저장된 진행률과 가중치. */
public record WeightedRate(BigDecimal rate, BigDecimal weight) {
}
