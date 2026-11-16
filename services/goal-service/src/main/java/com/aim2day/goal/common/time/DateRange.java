package com.aim2day.goal.common.time;

import java.time.LocalDate;

/** 양 끝을 포함하는 날짜 범위. */
public record DateRange(LocalDate startDate, LocalDate endDate) {
}
