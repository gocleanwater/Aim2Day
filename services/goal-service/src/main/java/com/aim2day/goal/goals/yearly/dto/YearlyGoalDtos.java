package com.aim2day.goal.goals.yearly.dto;

import com.aim2day.goal.common.time.UtcTime;
import com.aim2day.goal.goals.GoalStatus;
import com.aim2day.goal.goals.criteria.CriteriaSummary;
import com.aim2day.goal.goals.monthly.dto.MonthlyGoalDtos.MonthlyGoalDetailResponse;
import com.aim2day.goal.goals.yearly.YearlyGoal;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** 이 도메인의 요청·응답 형식 모음. */
public final class YearlyGoalDtos {

	private YearlyGoalDtos() {
	}

	/** 필드 검증은 서비스가 한다(제목은 공백을 지운 뒤 길이를 재야 하고, 연도 범위는 계정 시간대 기준이라서). */
	public record CreateYearlyGoalRequest(Integer targetYear, String title, String description, String color) {
	}

	public record ReorderYearlyGoalsRequest(Integer year, List<Long> goalIds) {
	}

	/**
	 * PATCH 본문. 각 필드는 세 상태를 구분한다.
	 *  - 필드 없음: Java null            -> 바꾸지 않는다.
	 *  - JSON null: Optional.empty()      -> 값을 지운다(연간 목표에서는 description만 허용, 나머지는 400).
	 *  - 값 있음: Optional.of(값)         -> 그 값으로 바꾼다.
	 * targetYear는 바꿀 수 없으므로 받지 않는다(보내도 무시된다).
	 *
	 * record가 아니라 setter를 쓰는 클래스인 이유: Jackson 3의 record는 생성자 인자가 비어 있을 때
	 * Optional 필드를 Optional.empty()로 채워 "필드 없음"과 "JSON null"이 구분되지 않는다.
	 * setter 방식은 JSON에 필드가 있을 때만 호출되므로 없는 필드는 초기값 null로 남는다.
	 */
	public static class UpdateYearlyGoalRequest {

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

	/** 연간 목표 객체에 삭제되지 않은 월간 목표와 그 하위 주간 목표 트리를 더한 응답(API 명세 §4.3). */
	public record YearlyGoalDetailResponse(YearlyGoalResponse goal, List<MonthlyGoalDetailResponse> monthlyGoals) {
	}

	public record YearlyGoalListResponse(int year, int activeCount, int maxCount, List<YearlyGoalResponse> items) {
	}

	/** 연간 목표 응답 객체(API 명세 §4.3). */
	public record YearlyGoalResponse(long id, int targetYear, String title, String description, String color,
			String displayColor, int displayOrder, GoalStatus status, GoalStatus pathStatus,
			@JsonProperty("isDelayed") boolean isDelayed, CriteriaSummary criteria, long version,
			Instant createdAt, Instant updatedAt) {

		/** today는 호출자가 계정 시간대로 계산해 넘긴다(클라이언트 시계는 쓰지 않는다). */
		public static YearlyGoalResponse of(YearlyGoal goal, CriteriaSummary criteria, LocalDate today) {
			GoalStatus status = goal.getStatus();
			// 연간 목표는 최상위라 자기 상태가 곧 경로 상태다. 보류·중단일 때만 값이 있다(GOAL-009).
			GoalStatus pathStatus = status == GoalStatus.ON_HOLD || status == GoalStatus.DROPPED ? status : null;
			// 종료일(해당 연도 12월 31일)이 오늘보다 앞서고 완료가 아니면 지연. 저장 상태는 바꾸지 않는다(DASH-004).
			boolean delayed = LocalDate.of(goal.getTargetYear(), 12, 31).isBefore(today)
					&& status != GoalStatus.COMPLETED;
			return new YearlyGoalResponse(goal.getId(), goal.getTargetYear(), goal.getTitle(), goal.getDescription(),
					goal.getColor(), goal.getColor(), goal.getDisplayOrder(), status, pathStatus, delayed, criteria,
					goal.getVersion(), UtcTime.toInstant(goal.getCreatedAt()), UtcTime.toInstant(goal.getUpdatedAt()));
		}
	}
}
