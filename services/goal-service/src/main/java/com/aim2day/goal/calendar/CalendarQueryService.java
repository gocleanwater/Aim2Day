package com.aim2day.goal.calendar;

import com.aim2day.goal.calendar.dto.CalendarDtos.CalendarDashboardResponse;
import com.aim2day.goal.calendar.dto.CalendarDtos.CalendarItemResponse;
import com.aim2day.goal.common.exception.BusinessException;
import com.aim2day.goal.common.tx.Tx;
import com.aim2day.goal.goals.yearly.YearlyGoal;
import com.aim2day.goal.reports.ReportErrorCode;
import com.aim2day.goal.settings.SettingsService;
import com.aim2day.goal.settings.UserTime;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 달력 기간 조회와 대시보드(API 명세 §4.11 GET). 항목 목록과 연결 목표(색·제목)를 함께 읽어 한 응답을 만들므로
 * 읽기 전용 REPEATABLE READ 트랜잭션 하나로 같은 스냅샷에서 읽는다(목록과 목표 정보가 서로 어긋나지 않게).
 */
@Service
public class CalendarQueryService {

	/** 기간 조회 최대 일수(from, to 포함). */
	public static final int MAX_RANGE_DAYS = 100;
	/** 대시보드의 앞·뒤 7일. */
	private static final int DASHBOARD_DAYS = 7;

	private final CalendarItemRepository items;
	private final CalendarAssembler assembler;
	private final SettingsService settingsService;
	private final TransactionTemplate readTx;

	public CalendarQueryService(CalendarItemRepository items, CalendarAssembler assembler,
			SettingsService settingsService, PlatformTransactionManager txManager) {
		this.items = items;
		this.assembler = assembler;
		this.settingsService = settingsService;
		this.readTx = Tx.readOnly(txManager);
	}

	/**
	 * [from, to]와 겹치는 항목. yearlyGoalIds가 null이면 목표 조건이 없고(모든 목표), 값이 있으면 그 목표(지금 보이는 것)에
	 * 연결된 항목만 고른다. includeUnlinked는 "목표 없음"으로 보이는 항목(연결 없음 + 연결된 목표가 삭제됨)을 더할지다.
	 * 목표 가시성은 응답의 yearlyGoal과 같은 기준이라 응답에서 null인 항목이 곧 미연결로 걸러진다.
	 */
	public List<CalendarItemResponse> range(long userId, LocalDate from, LocalDate to, List<Long> yearlyGoalIds,
			boolean includeUnlinked) {
		if (to.isBefore(from) || ChronoUnit.DAYS.between(from, to) + 1 > MAX_RANGE_DAYS) {
			throw new BusinessException(ReportErrorCode.REPORT_INVALID_PERIOD, Map.of("maxDays", MAX_RANGE_DAYS));
		}
		LocalDate today = settingsService.userTime(userId).today();
		Set<Long> wanted = yearlyGoalIds == null ? null : new HashSet<>(yearlyGoalIds);
		return readTx.execute(status -> {
			List<CalendarItem> found = items.findOverlapping(userId, from, to);
			Map<Long, YearlyGoal> goals = assembler.visibleGoals(userId, found);
			List<CalendarItemResponse> result = new ArrayList<>();
			for (CalendarItem item : found) {
				boolean linked = item.getYearlyGoalId() != null && goals.containsKey(item.getYearlyGoalId());
				boolean keep;
				if (!linked) {
					keep = includeUnlinked;
				} else {
					keep = wanted == null || wanted.contains(item.getYearlyGoalId());
				}
				if (keep) {
					result.add(assembler.response(item, goals, today));
				}
			}
			return result;
		});
	}

	/**
	 * 대시보드용 달력 요약을 만든다(GET /api/calendar/dashboard).
	 * 처리: 계정 시간대 기준 오늘을 정하고, 읽기 전용 트랜잭션에서 미완료 TASK 전체, 앞으로 7일 안의 EVENT, 지난 7일 안에 끝난 EVENT를 읽는다.
	 * 반환: 오늘 날짜와 세 목록(지난 일정에는 끝난 지 며칠 지났는지 포함).
	 */
	public CalendarDashboardResponse dashboard(long userId) {
		// 오늘은 계정 시간대 기준이다. 트랜잭션 밖에서 먼저 읽는다(SettingsService는 자기 트랜잭션을 쓴다).
		UserTime time = settingsService.userTime(userId);
		LocalDate today = time.today();
		return readTx.execute(status -> {
			List<CalendarItem> tasks = items.findOpenTasks(userId);
			List<CalendarItem> events = items.findUpcomingEvents(userId, today, today.plusDays(DASHBOARD_DAYS));
			List<CalendarItem> past = items.findPastEvents(userId, today.minusDays(DASHBOARD_DAYS), today);
			List<CalendarItem> all = new ArrayList<>(tasks);
			all.addAll(events);
			all.addAll(past);
			Map<Long, YearlyGoal> goals = assembler.visibleGoals(userId, all);
			return new CalendarDashboardResponse(today,
					tasks.stream().map(i -> assembler.response(i, goals, today)).toList(),
					events.stream().map(i -> assembler.response(i, goals, today)).toList(),
					past.stream().map(i -> CalendarDashboardResponse.PastEvent.of(assembler.response(i, goals, today),
							ChronoUnit.DAYS.between(i.getEndDate(), today))).toList());
		});
	}
}
