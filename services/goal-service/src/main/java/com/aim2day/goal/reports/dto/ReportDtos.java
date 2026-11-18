package com.aim2day.goal.reports.dto;

import com.aim2day.goal.actions.dto.ActionDtos.ActionResponse.GoalPathView;
import com.aim2day.goal.reports.PeriodReview;
import com.aim2day.goal.reports.PeriodType;
import com.aim2day.goal.reports.ReportPeriod;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** 이 도메인의 요청·응답 형식 모음. */
public final class ReportDtos {

	private ReportDtos() {
	}

	/** 직접 회고 저장 본문. PUT은 전체 저장이라 빠진 필드는 null로 저장한다(2026-11-18 사용자 결정). */
	public record PutReviewRequest(String improvements, String nextFocus, Long version) {
	}

	/** 기간 리포트(API 명세 §4.12). 기록이 없으면 0과 빈 배열이다(추정하지 않는다). review가 없으면 null이다. */
	public record ReportResponse(PeriodType periodType, String period, LocalDate startDate, LocalDate endDate,
			Summary summary, List<ReportAction> actions, List<ReportResult> results, List<GoalProgressItem> goalProgress,
			ReviewResponse review) {

		public record Summary(int completedActionCount, int activeDayCount) {
		}

		public record ReportAction(long id, String title, LocalDate performedDate, GoalPathView goalPath,
				String resultSummary) {
		}

		public record ReportResult(long actionId, LocalDate performedDate, String resultSummary) {
		}

		/** 연간 목표 하나의 달성률 변화. NOT_CONFIGURED면 숫자·라벨이 모두 null이다. */
		public record GoalProgressItem(long yearlyGoalId, String title, String color, State state, StartBase startBase,
				String startLabel, String endLabel, BigDecimal startRate, BigDecimal endRate, BigDecimal changePp,
				LocalDate observedFrom, String note) {
		}

		public enum State {
			COMPARABLE, CRITERIA_REVISED, NOT_CONFIGURED
		}

		public enum StartBase {
			PREVIOUS_PERIOD_END, INITIAL_BASELINE
		}
	}

	/** 기준 월에 끝나는 리포트 기간 탭(API 명세 §4.12). 순서는 월간, 분기, 연간이다. */
	public record ReportTabsResponse(String month, List<Tab> tabs) {

		public record Tab(PeriodType periodType, String period, String label, LocalDate startDate, LocalDate endDate) {

			public static Tab of(ReportPeriod p) {
				return new Tab(p.type(), p.value(), p.tabLabel(), p.start(), p.end());
			}
		}
	}

	/** 직접 회고(API 명세 §4.12). 서버는 사용자가 쓴 내용을 그대로 돌려주며 AI 문장을 넣지 않는다. */
	public record ReviewResponse(String improvements, String nextFocus, long version) {

		public static ReviewResponse of(PeriodReview r) {
			return new ReviewResponse(r.getImprovements(), r.getNextFocus(), r.getVersion());
		}
	}
}
