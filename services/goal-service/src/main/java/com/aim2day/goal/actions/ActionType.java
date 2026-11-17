package com.aim2day.goal.actions;

/** GOAL은 주간 목표에 연결된 핵심 행동, MAINTENANCE는 목표 없는 "그 밖의 할 일"이다. DB에는 컬럼이 없고 weekly_goal_id의 NULL 여부로 정한다. */
public enum ActionType {
	GOAL, MAINTENANCE
}
