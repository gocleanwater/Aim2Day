package com.aim2day.goal.calendar;

import com.aim2day.goal.calendar.dto.CalendarItemResponse;
import com.aim2day.goal.common.time.UtcTime;
import com.aim2day.goal.goals.yearly.YearlyGoal;
import com.aim2day.goal.goals.yearly.YearlyGoalRepository;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 달력 항목 -> 응답 변환. 연결된 연간 목표는 한 번에 읽는다(항목마다 읽지 않는다).
 * 삭제된 목표(deleted_at 있음)와 타인 소유 목표는 "연결 없음"으로 본다. 연결 행은 그대로 두므로(복원하면 다시 보인다)
 * 응답과 필터에서만 숨긴다.
 */
@Component
public class CalendarAssembler {

	private final YearlyGoalRepository yearlies;

	public CalendarAssembler(YearlyGoalRepository yearlies) {
		this.yearlies = yearlies;
	}

	/** 항목들이 가리키는 목표 중 지금 보이는(소유자가 같고 삭제되지 않은) 것만 id로 모은다. */
	public Map<Long, YearlyGoal> visibleGoals(long userId, List<CalendarItem> items) {
		Set<Long> ids = new HashSet<>();
		for (CalendarItem item : items) {
			if (item.getYearlyGoalId() != null) {
				ids.add(item.getYearlyGoalId());
			}
		}
		Map<Long, YearlyGoal> visible = new HashMap<>();
		if (ids.isEmpty()) {
			return visible;
		}
		for (YearlyGoal goal : yearlies.findAllByIdIn(ids)) {
			if (goal.getDeletedAt() == null && goal.getUserId() == userId) {
				visible.put(goal.getId(), goal);
			}
		}
		return visible;
	}

	public List<CalendarItemResponse> responses(long userId, List<CalendarItem> items, LocalDate today) {
		Map<Long, YearlyGoal> goals = visibleGoals(userId, items);
		return items.stream().map(item -> response(item, goals, today)).toList();
	}

	public CalendarItemResponse response(CalendarItem item, Map<Long, YearlyGoal> goals, LocalDate today) {
		YearlyGoal goal = item.getYearlyGoalId() == null ? null : goals.get(item.getYearlyGoalId());
		CalendarItemResponse.YearlyRef ref = goal == null ? null
				: new CalendarItemResponse.YearlyRef(goal.getId(), goal.getTitle(), goal.getColor());
		return new CalendarItemResponse(item.getId(), item.getItemType(), item.getTitle(), item.getDescription(),
				item.getStartDate(), item.getEndDate(), item.isCompleted(), UtcTime.toInstant(item.getCompletedAt()),
				ref, ChronoUnit.DAYS.between(today, item.getStartDate()), item.getVersion());
	}
}
