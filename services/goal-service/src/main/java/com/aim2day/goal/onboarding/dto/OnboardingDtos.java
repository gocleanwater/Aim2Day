package com.aim2day.goal.onboarding.dto;

import com.aim2day.goal.actions.ActionType;
import com.aim2day.goal.common.time.UtcTime;
import com.aim2day.goal.goals.weekly.MeasurementType;
import com.aim2day.goal.onboarding.OnboardingDraft;
import com.fasterxml.jackson.annotation.JsonRawValue;
import com.fasterxml.jackson.annotation.JsonSetter;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.annotation.JsonDeserialize;

/** 이 도메인의 요청·응답 형식 모음. */
public final class OnboardingDtos {

	private OnboardingDtos() {
	}

	/**
	 * 온보딩 행동 항목(API 명세 §4.9 생성 본문 + weeklyGoalTempId·monthlyGoalTempId).
	 * 그 밖의 할 일(MAINTENANCE)은 연결 필드·기여량을 "보내는 것 자체"가 400이라 키가 있었는지를 따로 기억한다
	 * (CreateActionRequest와 같은 방식). 값 접근자는 생략·null 모두 null이다.
	 */
	public static class ActionItem {

		private ActionType type;
		private String monthlyGoalTempId;
		private Long monthlyGoalId;
		private String weeklyGoalTempId;
		private Long weeklyGoalId;
		private String title;
		private LocalDate performedDate;
		private LocalDate dueDate;
		private BigDecimal contributionValue;
		private boolean monthlyGoalTempIdPresent;
		private boolean monthlyGoalIdPresent;
		private boolean weeklyGoalTempIdPresent;
		private boolean weeklyGoalIdPresent;
		private boolean contributionValuePresent;

		public ActionType type() {
			return type;
		}

		public String monthlyGoalTempId() {
			return monthlyGoalTempId;
		}

		public Long monthlyGoalId() {
			return monthlyGoalId;
		}

		public String weeklyGoalTempId() {
			return weeklyGoalTempId;
		}

		public Long weeklyGoalId() {
			return weeklyGoalId;
		}

		public String title() {
			return title;
		}

		public LocalDate performedDate() {
			return performedDate;
		}

		public LocalDate dueDate() {
			return dueDate;
		}

		public BigDecimal contributionValue() {
			return contributionValue;
		}

		public boolean monthlyGoalTempIdPresent() {
			return monthlyGoalTempIdPresent;
		}

		public boolean monthlyGoalIdPresent() {
			return monthlyGoalIdPresent;
		}

		public boolean weeklyGoalTempIdPresent() {
			return weeklyGoalTempIdPresent;
		}

		public boolean weeklyGoalIdPresent() {
			return weeklyGoalIdPresent;
		}

		public boolean contributionValuePresent() {
			return contributionValuePresent;
		}

		@JsonSetter("type")
		public void setType(ActionType type) {
			this.type = type;
		}

		@JsonSetter("monthlyGoalTempId")
		public void setMonthlyGoalTempId(String monthlyGoalTempId) {
			this.monthlyGoalTempId = monthlyGoalTempId;
			this.monthlyGoalTempIdPresent = true;
		}

		@JsonSetter("monthlyGoalId")
		public void setMonthlyGoalId(Long monthlyGoalId) {
			this.monthlyGoalId = monthlyGoalId;
			this.monthlyGoalIdPresent = true;
		}

		@JsonSetter("weeklyGoalTempId")
		public void setWeeklyGoalTempId(String weeklyGoalTempId) {
			this.weeklyGoalTempId = weeklyGoalTempId;
			this.weeklyGoalTempIdPresent = true;
		}

		@JsonSetter("weeklyGoalId")
		public void setWeeklyGoalId(Long weeklyGoalId) {
			this.weeklyGoalId = weeklyGoalId;
			this.weeklyGoalIdPresent = true;
		}

		@JsonSetter("title")
		public void setTitle(String title) {
			this.title = title;
		}

		@JsonSetter("performedDate")
		public void setPerformedDate(LocalDate performedDate) {
			this.performedDate = performedDate;
		}

		@JsonSetter("dueDate")
		public void setDueDate(LocalDate dueDate) {
			this.dueDate = dueDate;
		}

		@JsonSetter("contributionValue")
		public void setContributionValue(BigDecimal contributionValue) {
			this.contributionValue = contributionValue;
			this.contributionValuePresent = true;
		}
	}

	/**
	 * 온보딩 일괄 저장 본문(API 명세 §4.2 complete). 새 항목은 tempId로 서로 참조하고, 기존 목표는 실제 id로 참조한다.
	 * 부모 지정은 ...TempId와 ...Id 중 하나만 쓴다. 필드 검증은 개별 생성 API의 규칙을 서비스가 재사용해 한다.
	 * 연간 목표는 현재 연도, 월간은 현재 달, 주간은 현재 주로 서버가 정하므로 기간·연도는 받지 않는다.
	 */
	public record CompleteRequest(List<YearlyItem> yearlyGoals, List<MonthlyItem> monthlyGoals,
			List<WeeklyItem> weeklyGoals, List<ActionItem> actions) {

		public record YearlyItem(String tempId, String title, String description, String color) {
		}

		public record MonthlyItem(String tempId, String yearlyGoalTempId, Long yearlyGoalId, String title,
				String description, String color) {
		}

		public record WeeklyItem(String tempId, String monthlyGoalTempId, Long monthlyGoalId, String title,
				String description, String color, MeasurementType measurementType, BigDecimal targetValue,
				BigDecimal weight) {
		}
	}

	/** 만든 항목의 tempId -> id 대응표. actions는 요청 순서의 id 목록이다. */
	public record CompleteResponse(Map<String, Long> yearlyGoals, Map<String, Long> monthlyGoals,
			Map<String, Long> weeklyGoals, List<Long> actions) {
	}

	/**
	 * 임시 저장 응답. draftData는 DB가 돌려준 JSON 텍스트를 그대로 응답에 끼워 넣는다(@JsonRawValue).
	 * 한 번 파싱해 다시 쓰면 큰 숫자·소수 정밀도가 double로 깎이므로, 저장한 값 그대로 돌려주려는 것이다.
	 */
	public record DraftResponse(int currentStep, @JsonRawValue String draftData, long version, Instant updatedAt) {

		public static DraftResponse of(OnboardingDraft draft) {
			return new DraftResponse(draft.getCurrentStep(), draft.getDraftData(), draft.getVersion(),
					UtcTime.toInstant(draft.getUpdatedAt()));
		}
	}

	/**
	 * 임시 저장 본문. draftData는 서버가 해석하지 않는 자유 JSON이라 JSON 텍스트로 받는다(RawJsonDeserializer).
	 * 값 검증(단계 1~4, 필수, 64KB)은 서비스가 한다.
	 */
	public record PutDraftRequest(Integer currentStep, @JsonDeserialize(using = RawJsonDeserializer.class) String draftData,
			Long version) {
	}

	public record StatusResponse(boolean needsOnboarding, boolean hasDraft, int currentYear, int activeYearlyGoalCount,
			int maxYearlyGoalCount, String firstQuestion) {
	}
}
