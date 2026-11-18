package com.aim2day.goal.onboarding;

import static org.assertj.core.api.Assertions.assertThat;

import com.aim2day.goal.support.GoalTestSupport;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;

class OnboardingStatusApiTests extends OnboardingTestBase {

	private static final String QUESTION_OTHER = "올해 남은 기간에 어떤 일에 집중하고 싶나요?";
	private static final String QUESTION_JANUARY = "올해 어떤 일에 집중하고 싶나요?";

	@Test
	void newUserNeedsOnboarding() throws Exception {
		api.expect(api.get(STATUS, 1L), 200,
				"$.code", "SUCCESS",
				"$.data.needsOnboarding", true,
				"$.data.hasDraft", false,
				"$.data.currentYear", 2026,
				"$.data.activeYearlyGoalCount", 0,
				"$.data.maxYearlyGoalCount", 3,
				"$.data.firstQuestion", QUESTION_OTHER);
	}

	@Test
	void userWithActiveGoalAndNoDraftDoesNotNeedOnboarding() throws Exception {
		api.yearly(1L, "연간");
		api.expect(api.get(STATUS, 1L), 200,
				"$.data.needsOnboarding", false, "$.data.hasDraft", false, "$.data.activeYearlyGoalCount", 1);
	}

	@Test
	void draftKeepsOnboardingNeededEvenWithGoals() throws Exception {
		api.yearly(1L, "연간");
		putDraft(1L, 2, "{\"a\":1}");
		api.expect(api.get(STATUS, 1L), 200,
				"$.data.needsOnboarding", true, "$.data.hasDraft", true, "$.data.activeYearlyGoalCount", 1);
	}

	@Test
	void countsEveryStatusButNotDeletedGoals() throws Exception {
		long a = api.yearly(1L, "진행");
		long b = api.yearly(1L, "곧 완료");
		long c = api.yearly(1L, "곧 삭제");
		jdbc.update("UPDATE yearly_goals SET status = 'COMPLETED' WHERE id = ?", b);
		jdbc.update("UPDATE yearly_goals SET deleted_at = CURRENT_TIMESTAMP WHERE id = ?", c);
		assertThat(a).isPositive();
		// 상태와 무관하게 소프트 삭제되지 않은 목표를 센다.
		api.expect(api.get(STATUS, 1L), 200, "$.data.activeYearlyGoalCount", 2, "$.data.needsOnboarding", false);
	}

	@Test
	void onlyDeletedGoalsStillNeedOnboarding() throws Exception {
		long a = api.yearly(1L, "삭제됨");
		jdbc.update("UPDATE yearly_goals SET deleted_at = CURRENT_TIMESTAMP WHERE id = ?", a);
		api.expect(api.get(STATUS, 1L), 200, "$.data.activeYearlyGoalCount", 0, "$.data.needsOnboarding", true);
	}

	@Test
	void ignoresOtherYearsAndOtherUsers() throws Exception {
		GoalTestSupport.seedYearly(jdbc, 1L, 2027, "내년");
		GoalTestSupport.seedYearly(jdbc, 2L, 2026, "남의 목표");
		putDraft(2L, 1, "{}");
		api.expect(api.get(STATUS, 1L), 200,
				"$.data.activeYearlyGoalCount", 0, "$.data.hasDraft", false, "$.data.needsOnboarding", true);
	}

	@Test
	void januaryUsesTheJanuaryQuestion() throws Exception {
		clock.set(Instant.parse("2027-01-12T03:00:00Z"));
		api.expect(api.get(STATUS, 1L), 200,
				"$.data.currentYear", 2027, "$.data.firstQuestion", QUESTION_JANUARY);
	}

	@Test
	void yearAndQuestionFollowTheAccountTimezone() throws Exception {
		// UTC 2026-12-31 16:00은 서울(기본 시간대)로 2027-01-01 01:00이다. 올해·1월 여부 모두 서울 날짜를 따른다.
		clock.set(Instant.parse("2026-12-31T16:00:00Z"));
		api.expect(api.get(STATUS, 1L), 200,
				"$.data.currentYear", 2027, "$.data.firstQuestion", QUESTION_JANUARY);
		// 같은 시각 UTC 날짜는 아직 12월 31일이다. 시간대를 UTC로 바꾸면 2026년·"남은 기간" 문구다.
		MvcResult settings = api.put("/api/settings", 1L,
				"{\"weekStartDay\":\"SUNDAY\",\"timezone\":\"UTC\",\"version\":0}");
		assertThat(api.status(settings)).as(api.body(settings)).isEqualTo(200);
		api.expect(api.get(STATUS, 1L), 200,
				"$.data.currentYear", 2026, "$.data.firstQuestion", QUESTION_OTHER);
	}

	@Test
	void requiresLogin() throws Exception {
		mockMvc.perform(MockMvcRequestBuilders.get(STATUS)).andExpect(MockMvcResultMatchers.status().isUnauthorized());
	}
}
