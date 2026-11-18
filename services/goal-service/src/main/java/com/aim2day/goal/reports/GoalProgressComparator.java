package com.aim2day.goal.reports;

import com.aim2day.goal.goals.yearly.YearlyGoal;
import com.aim2day.goal.progress.GoalProgressLog;
import com.aim2day.goal.progress.ProgressChangeType;
import com.aim2day.goal.reports.dto.ReportDtos.ReportResponse.GoalProgressItem;
import com.aim2day.goal.reports.dto.ReportDtos.ReportResponse.StartBase;
import com.aim2day.goal.reports.dto.ReportDtos.ReportResponse.State;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 연간 목표 하나의 "기간 시작 대비 달성률 변화"를 진행 기록(goal_progress_logs)으로 계산한다(ERD §4.2, FN-REPORT-02).
 * 현재 달성률을 다시 계산하지 않고 기록된 관측값만 쓰므로, 기준이 나중에 바뀌어도 과거 리포트의 숫자가 변하지 않는다.
 * 상태·값 결정은 DB와 무관한 순수 계산이라 정적 메서드로 두어 단위 테스트하기 쉽게 했다.
 */
final class GoalProgressComparator {

	private GoalProgressComparator() {
	}

	/**
	 * @param logs 이 목표의 연간 진행 기록 전체, 저장 순서(id) 오름차순
	 * @param today 계정 시간대의 오늘
	 *
	 * 판정 기준은 "일어난 순서"다. 사용자가 시간대를 바꾸면 나중에 쓴 기록의 log_date가 앞 기록보다 이를 수 있다
	 * (예: 11/18에 확정한 뒤 시간대를 바꿔 11/17 날짜로 기록). 날짜순으로 정렬하면 나중 기록이 "첫 기록"이 되어
	 * 최초 기준선을 잘못 잡는다. 그래서 id 순으로 읽고, 각 기록의 유효 날짜를 "그 기록까지의 log_date 누적 최댓값"으로
	 * 정한다(앞 기록보다 이른 날짜는 앞 기록의 날짜로 본다). 유효 날짜는 id 순으로 단조 증가하므로 기간 경계 판정이
	 * 항상 일관된 접두 구간이 된다. 최초 기준선은 언제나 id 순 첫 기록(확정 때 기록)이다.
	 */
	static GoalProgressItem compare(YearlyGoal goal, List<GoalProgressLog> logs, ReportPeriod period, LocalDate today) {
		LocalDate effectiveEnd = period.effectiveEnd(today);
		// 실질 끝까지 일어난 기록(유효 날짜 <= 실질 끝)만 남긴다. 단조 증가라 접두 구간이다.
		List<GoalProgressLog> known = new ArrayList<>();
		List<LocalDate> dates = new ArrayList<>();
		LocalDate running = null;
		for (GoalProgressLog log : logs) {
			running = running == null || log.getLogDate().isAfter(running) ? log.getLogDate() : running;
			if (running.isAfter(effectiveEnd)) {
				break;
			}
			known.add(log);
			dates.add(running);
		}
		if (known.isEmpty()) {
			// 실질 끝까지 기준을 한 번도 확정하지 않았다. 관측값이 없으므로 0%로 추정하지 않고 숫자를 모두 비운다.
			return new GoalProgressItem(goal.getId(), goal.getTitle(), goal.getColor(), State.NOT_CONFIGURED, null,
					null, null, null, null, null, null, "달성 기준 설정 전");
		}
		// 기간 시작 전 마지막 관측값(있으면 "지난 기간 말" 값). 같은 유효 날짜 안에서는 id가 큰 쪽이 마지막이다.
		GoalProgressLog before = null;
		LocalDate firstDate = null;
		boolean revisedInPeriod = false;
		for (int i = 0; i < known.size(); i++) {
			GoalProgressLog log = known.get(i);
			if (dates.get(i).isBefore(period.start())) {
				before = log;
				continue;
			}
			if (firstDate == null) {
				firstDate = dates.get(i);
			}
			if (log.getChangeType() == ProgressChangeType.CRITERIA_REVISION) {
				// 기간 안(시작일~실질 끝)의 개정. 기간 뒤의 개정은 known에 들어오지 않으므로 과거 리포트는 계속 비교 가능하다.
				revisedInPeriod = true;
			}
		}
		BigDecimal endRate = known.get(known.size() - 1).getNewRate();
		StartBase startBase;
		String startLabel;
		BigDecimal startRate;
		LocalDate observedFrom;
		String baselineNote;
		if (before != null) {
			startBase = StartBase.PREVIOUS_PERIOD_END;
			startLabel = period.previousEndLabel();
			startRate = before.getNewRate();
			observedFrom = period.start();
			baselineNote = null;
		} else {
			// 시작 전 기록이 없다 = 기간 중 처음 기준을 확정했다. 이때의 최초 기준선이 시작 값이다.
			startBase = StartBase.INITIAL_BASELINE;
			startRate = known.get(0).getNewRate();
			observedFrom = firstDate;
			String mmdd = mmdd(observedFrom);
			startLabel = "기록 시작 (" + mmdd + ")";
			baselineNote = mmdd + " 기준 확정 이후 변화";
		}
		// 진행 중인 기간(오늘이 끝일인 날 포함)의 끝 막대는 "오늘" 값이다. 다음 날부터 "11월 말"이다.
		String endLabel = !period.end().isBefore(today) ? "오늘 (" + mmdd(today) + ")" : period.endLabel();
		State state = revisedInPeriod ? State.CRITERIA_REVISED : State.COMPARABLE;
		// 기준·배점이 바뀐 기간은 같은 잣대의 변화가 아니므로 changePp만 비운다(명세 표). 나머지 값은 COMPARABLE과 같다.
		BigDecimal changePp = revisedInPeriod ? null : endRate.subtract(startRate);
		String note = revisedInPeriod ? "기준·배점 변경으로 비교 불가" : baselineNote;
		return new GoalProgressItem(goal.getId(), goal.getTitle(), goal.getColor(), state, startBase, startLabel,
				endLabel, startRate, endRate, changePp, observedFrom, note);
	}

	private static String mmdd(LocalDate date) {
		return String.format("%02d.%02d", date.getMonthValue(), date.getDayOfMonth());
	}
}
