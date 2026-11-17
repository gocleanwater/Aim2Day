package com.aim2day.goal.goals;

import java.time.LocalDate;

/**
 * 목표 경로(자신 -> 월간 -> 연간)를 따라 올라가며 정하는 표시값. 응답을 만드는 모든 단계가 같은 규칙을 쓰도록 한곳에 둔다.
 * 인자는 항상 "자신부터 위로" 순서다.
 */
public final class GoalPath {

	private GoalPath() {
	}

	/** 처음 만나는 보류·중단 상태. 없으면 null이다. 숨기지 않고 화면이 이름 옆에 표시한다(GOAL-009). */
	public static GoalStatus pathStatus(GoalStatus... selfFirst) {
		for (GoalStatus status : selfFirst) {
			if (status == GoalStatus.ON_HOLD || status == GoalStatus.DROPPED) {
				return status;
			}
		}
		return null;
	}

	/** 처음 만나는 non-null 색. 월간·주간의 null 색은 부모 색을 상속한다(GOAL-011). 연간 색은 null이 아니다. */
	public static String displayColor(String... selfFirst) {
		for (String color : selfFirst) {
			if (color != null) {
				return color;
			}
		}
		return null;
	}

	/** 종료일이 오늘보다 앞서고 완료가 아니면 지연이다. 저장 상태는 바꾸지 않는다(DASH-004, API 명세 §2.7). */
	public static boolean isDelayed(LocalDate endDate, GoalStatus status, LocalDate today) {
		return endDate.isBefore(today) && status != GoalStatus.COMPLETED;
	}
}
