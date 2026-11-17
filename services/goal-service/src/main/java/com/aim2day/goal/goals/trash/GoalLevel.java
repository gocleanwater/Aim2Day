package com.aim2day.goal.goals.trash;

import com.aim2day.goal.common.exception.BusinessException;
import com.aim2day.goal.common.exception.CommonErrorCode;
import java.util.Map;

/** 삭제·복원 API 경로의 {level}(yearly, monthly, weekly). 단계 순서(연간 -> 월간 -> 주간)가 휴지통 정렬 순서다. */
public enum GoalLevel {
	YEARLY, MONTHLY, WEEKLY;

	/** 알 수 없는 level은 존재하지 않는 리소스로 취급해 404로 돌려준다(경로 id는 모르므로 그대로 담는다). */
	public static GoalLevel fromPath(String level, long id) {
		return switch (level) {
			case "yearly" -> YEARLY;
			case "monthly" -> MONTHLY;
			case "weekly" -> WEEKLY;
			default -> throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND,
					Map.of("resource", "GOAL", "id", id));
		};
	}
}
