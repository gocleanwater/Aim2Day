package com.aim2day.goal.reports.dto;

import com.aim2day.goal.reports.PeriodType;
import com.aim2day.goal.reports.ReportPeriod;
import java.time.LocalDate;
import java.util.List;

/** 기준 월에 끝나는 리포트 기간 탭(API 명세 §4.12). 순서는 월간, 분기, 연간이다. */
public record ReportTabsResponse(String month, List<Tab> tabs) {

	public record Tab(PeriodType periodType, String period, String label, LocalDate startDate, LocalDate endDate) {

		public static Tab of(ReportPeriod p) {
			return new Tab(p.type(), p.value(), p.tabLabel(), p.start(), p.end());
		}
	}
}
