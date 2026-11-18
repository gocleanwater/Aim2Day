package com.aim2day.goal.reports;

import com.aim2day.goal.actions.Action;
import com.aim2day.goal.actions.ActionAssembler;
import com.aim2day.goal.actions.ActionRepository;
import com.aim2day.goal.actions.dto.ActionDtos.ActionResponse.GoalPathView;
import com.aim2day.goal.common.exception.BusinessException;
import com.aim2day.goal.common.exception.FieldErrors;
import com.aim2day.goal.common.exception.ValidationReason;
import com.aim2day.goal.common.tx.Tx;
import com.aim2day.goal.goals.yearly.YearlyGoal;
import com.aim2day.goal.goals.yearly.YearlyGoalRepository;
import com.aim2day.goal.progress.GoalProgressLog;
import com.aim2day.goal.progress.ProgressLogRepository;
import com.aim2day.goal.reports.dto.ReportDtos.ReportResponse;
import com.aim2day.goal.reports.dto.ReportDtos.ReportResponse.GoalProgressItem;
import com.aim2day.goal.reports.dto.ReportDtos.ReportResponse.ReportAction;
import com.aim2day.goal.reports.dto.ReportDtos.ReportResponse.ReportResult;
import com.aim2day.goal.reports.dto.ReportDtos.ReportResponse.Summary;
import com.aim2day.goal.reports.dto.ReportDtos.ReportTabsResponse;
import com.aim2day.goal.reports.dto.ReportDtos.ReportTabsResponse.Tab;
import com.aim2day.goal.reports.dto.ReportDtos.ReviewResponse;
import com.aim2day.goal.settings.SettingsService;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 리포트 탭과 기간 리포트 조회(API 명세 §4.12 GET). 모든 날짜는 계정 시간대 기준이다.
 *
 * 리포트는 행동·목표·진행 기록·회고를 한 응답에 모으므로, 읽기 전용 REPEATABLE READ 트랜잭션 하나에서 읽어
 * 같은 스냅샷을 보게 한다(요약 건수와 목록, 목표별 변화가 서로 어긋나지 않게). 오늘은 SettingsService가 자기
 * 트랜잭션을 쓰므로 트랜잭션 밖에서 먼저 구한다.
 */
@Service
public class ReportService {

	private static final Pattern MONTH = Pattern.compile("^\\d{4}-(0[1-9]|1[0-2])$");

	private final ActionRepository actions;
	private final ActionAssembler actionAssembler;
	private final YearlyGoalRepository yearlies;
	private final ProgressLogRepository logs;
	private final PeriodReviewRepository reviews;
	private final SettingsService settingsService;
	private final TransactionTemplate readTx;

	public ReportService(ActionRepository actions, ActionAssembler actionAssembler, YearlyGoalRepository yearlies,
			ProgressLogRepository logs, PeriodReviewRepository reviews, SettingsService settingsService,
			PlatformTransactionManager txManager) {
		this.actions = actions;
		this.actionAssembler = actionAssembler;
		this.yearlies = yearlies;
		this.logs = logs;
		this.reviews = reviews;
		this.settingsService = settingsService;
		this.readTx = Tx.readOnly(txManager);
	}

	/**
	 * 기준 월에 끝나는 기간 탭. 월간은 항상, 분기는 3·6·9·12월, 연간은 12월에만 둔다(결정 20).
	 * month를 생략하면 이번 달이다. 미래 달은 422다. 오늘(계정 시간대)만 SettingsService.userTime이 자체 트랜잭션으로 읽고,
	 * 탭 계산은 DB를 읽지 않으므로 이 메서드에는 트랜잭션이 없다.
	 */
	public ReportTabsResponse tabs(long userId, String rawMonth) {
		LocalDate today = settingsService.userTime(userId).today();
		YearMonth month;
		if (rawMonth == null) {
			month = YearMonth.from(today);
		} else {
			if (!MONTH.matcher(rawMonth).matches()) {
				throw new FieldErrors().add("month", ValidationReason.INVALID_FORMAT).toException();
			}
			try {
				month = YearMonth.parse(rawMonth);
			} catch (DateTimeParseException e) {
				throw new FieldErrors().add("month", ValidationReason.INVALID_FORMAT).toException();
			}
			if (month.atDay(1).isAfter(today)) {
				throw new BusinessException(ReportErrorCode.REPORT_INVALID_PERIOD);
			}
		}
		List<Tab> tabs = new ArrayList<>();
		tabs.add(Tab.of(ReportPeriod.ofMonth(month)));
		int m = month.getMonthValue();
		// 분기·연간 탭은 그 기간이 끝난 뒤(오늘 > 끝일)에만 보여 준다(사용자 결정). 월간은 진행 중인 이번 달도 항상 보인다.
		// GET /api/reports 자체는 진행 중인 분기·연간도 계산해 주므로 탭만 숨기는 것이다.
		if (m % 3 == 0) {
			ReportPeriod quarter = ReportPeriod.ofQuarter(month.getYear(), m / 3);
			if (quarter.end().isBefore(today)) {
				tabs.add(Tab.of(quarter));
			}
		}
		if (m == 12) {
			ReportPeriod year = ReportPeriod.ofYear(month.getYear());
			if (year.end().isBefore(today)) {
				tabs.add(Tab.of(year));
			}
		}
		return new ReportTabsResponse(month.toString(), tabs);
	}

	public ReportResponse report(long userId, String periodType, String period) {
		ReportPeriod p = ReportPeriod.parse(periodType, period);
		LocalDate today = settingsService.userTime(userId).today();
		if (p.isFuture(today)) {
			throw new BusinessException(ReportErrorCode.REPORT_INVALID_PERIOD);
		}
		return readTx.execute(status -> build(userId, p, today));
	}

	private ReportResponse build(long userId, ReportPeriod p, LocalDate today) {
		// 집계 대상은 수행일이 기간 안인 완료 GOAL 행동이다. 소유권은 쿼리의 user_id 조건이 보장한다.
		List<Action> done = actions.findCompletedGoalActionsBetween(userId, p.start(),
				p.effectiveEnd(today));
		// goalPath는 행동 응답과 같은 조립 코드로 한 번에 만든다(N+1 없음). 삭제된 경로도 현재 이름을 보여 준다.
		Map<Long, GoalPathView> paths = actionAssembler.goalPaths(done);
		List<ReportAction> actionItems = new ArrayList<>();
		List<ReportResult> results = new ArrayList<>();
		Set<LocalDate> activeDays = new HashSet<>();
		for (Action a : done) {
			actionItems.add(new ReportAction(a.getId(), a.getTitle(), a.getPerformedDate(), paths.get(a.getId()),
					a.getResultSummary()));
			activeDays.add(a.getPerformedDate());
			// 결과물은 resultSummary가 비어 있지 않은 행동만이다(공백만 있는 값은 비어 있는 것으로 본다).
			if (a.getResultSummary() != null && !a.getResultSummary().isBlank()) {
				results.add(new ReportResult(a.getId(), a.getPerformedDate(), a.getResultSummary()));
			}
		}
		PeriodReview review = reviews.findByUserIdAndPeriodTypeAndPeriodValue(userId, p.type().name(), p.value())
				.orElse(null);
		return new ReportResponse(p.type(), p.value(), p.start(), p.end(),
				new Summary(done.size(), activeDays.size()), actionItems, results, goalProgress(userId, p, today),
				review == null ? null : ReviewResponse.of(review));
	}

	/**
	 * 기간이 속한 연도의 연간 목표(영구 삭제되지 않은 것, 소프트 삭제 포함)별 달성률 변화.
	 * 진행 기록은 목표 전체를 한 쿼리로 읽어 목표별로 나눈다.
	 */
	private List<GoalProgressItem> goalProgress(long userId, ReportPeriod p, LocalDate today) {
		List<YearlyGoal> goals = yearlies.findByUserIdAndTargetYearOrderByDisplayOrderAscIdAsc(userId, p.year());
		if (goals.isEmpty()) {
			return List.of();
		}
		Map<Long, List<GoalProgressLog>> byGoal = new LinkedHashMap<>();
		for (GoalProgressLog log : logs.findYearlyLogs(userId, goals.stream().map(YearlyGoal::getId).toList())) {
			byGoal.computeIfAbsent(log.getTargetId(), k -> new ArrayList<>()).add(log);
		}
		return goals.stream()
				.map(g -> GoalProgressComparator.compare(g, byGoal.getOrDefault(g.getId(), List.of()), p, today))
				.toList();
	}
}
