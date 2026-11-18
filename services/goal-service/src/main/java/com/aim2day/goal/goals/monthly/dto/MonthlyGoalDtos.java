package com.aim2day.goal.goals.monthly.dto;

import com.aim2day.goal.goals.GoalPath;
import com.aim2day.goal.goals.GoalRef;
import com.aim2day.goal.goals.GoalStatus;
import com.aim2day.goal.goals.ProgressView;
import com.aim2day.goal.goals.monthly.MonthlyGoal;
import com.aim2day.goal.goals.weekly.dto.WeeklyGoalDtos.WeeklyGoalResponse;
import com.aim2day.goal.goals.yearly.YearlyGoal;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;

/** 이 도메인의 요청·응답 형식 모음. */
public final class MonthlyGoalDtos {

	private MonthlyGoalDtos() {
	}

	/** 대상 월은 받지 않는다. 서버가 계정 시간대 기준 현재 달로 만든다(GOAL-014). color가 null이면 부모 색을 상속한다. */
	public record CreateMonthlyGoalRequest(Long yearlyGoalId, String title, String description, String color) {
	}

	/**
	 * 월간 목표 객체에 하위 weeklyGoals를 더한 응답. 월간 목표 상세와 연간 목표 상세의 트리가 같은 모양이다.
	 * 월간 필드를 감싸지 않고 같은 수준에 펼쳐 명세 예시(월간 목표 객체 안에 weeklyGoals 배열)와 맞춘다.
	 */
	public record MonthlyGoalDetailResponse(long id, GoalRef yearlyGoal, String month, LocalDate startDate,
			LocalDate endDate, String title, String description, String color, String displayColor, GoalStatus status,
			GoalStatus pathStatus, @JsonProperty("isDelayed") boolean isDelayed, ProgressView progress,
			int weeklyGoalCount, long version, List<WeeklyGoalResponse> weeklyGoals) {

		public static MonthlyGoalDetailResponse of(MonthlyGoalResponse m, List<WeeklyGoalResponse> weeklyGoals) {
			return new MonthlyGoalDetailResponse(m.id(), m.yearlyGoal(), m.month(), m.startDate(), m.endDate(),
					m.title(), m.description(), m.color(), m.displayColor(), m.status(), m.pathStatus(), m.isDelayed(),
					m.progress(), m.weeklyGoalCount(), m.version(), weeklyGoals);
		}
	}

	/** 월간 목표 응답 객체(API 명세 §4.5). */
	public record MonthlyGoalResponse(long id, GoalRef yearlyGoal, String month, LocalDate startDate, LocalDate endDate,
			String title, String description, String color, String displayColor, GoalStatus status, GoalStatus pathStatus,
			@JsonProperty("isDelayed") boolean isDelayed, ProgressView progress, int weeklyGoalCount, long version) {

		public static MonthlyGoalResponse of(MonthlyGoal m, YearlyGoal y, int weeklyGoalCount, LocalDate today) {
			// 하위 주간 목표가 하나도 없을 때만 "기록 없음"이다. 있으면 0%도 기록이다(ERD §4.3).
			ProgressView progress = weeklyGoalCount == 0 ? ProgressView.noRecord()
					: ProgressView.recorded(m.getProgressRate());
			return new MonthlyGoalResponse(m.getId(), new GoalRef(y.getId(), y.getTitle()),
					YearMonth.from(m.getStartDate()).toString(), m.getStartDate(), m.getEndDate(), m.getTitle(),
					m.getDescription(), m.getColor(), GoalPath.displayColor(m.getColor(), y.getColor()), m.getStatus(),
					GoalPath.pathStatus(m.getStatus(), y.getStatus()),
					GoalPath.isDelayed(m.getEndDate(), m.getStatus(), today), progress, weeklyGoalCount, m.getVersion());
		}
	}

	/**
	 * PATCH 본문. 필드 없음은 Java null(바꾸지 않음), JSON null은 Optional.empty()다(UpdateYearlyGoalRequest와 같다).
	 * description null은 지움, color null은 "부모 색 상속으로 되돌림", title·status null은 400이다.
	 * 귀속 월과 부모 연간 목표는 바꿀 수 없으므로 받지 않는다.
	 */
	public static class UpdateMonthlyGoalRequest {

		private Optional<String> title;
		private Optional<String> description;
		private Optional<String> color;
		private Optional<GoalStatus> status;
		private Long version;

		public Optional<String> title() {
			return title;
		}

		public Optional<String> description() {
			return description;
		}

		public Optional<String> color() {
			return color;
		}

		public Optional<GoalStatus> status() {
			return status;
		}

		public Long version() {
			return version;
		}

		@JsonSetter("title")
		public void setTitle(Optional<String> title) {
			this.title = title;
		}

		@JsonSetter("description")
		public void setDescription(Optional<String> description) {
			this.description = description;
		}

		@JsonSetter("color")
		public void setColor(Optional<String> color) {
			this.color = color;
		}

		@JsonSetter("status")
		public void setStatus(Optional<GoalStatus> status) {
			this.status = status;
		}

		@JsonSetter("version")
		public void setVersion(Long version) {
			this.version = version;
		}
	}
}
