package com.aim2day.goal.actions;

import com.aim2day.goal.actions.dto.ActionDtos.ActionResponse;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.ToIntFunction;

/**
 * 행동 목록 정렬(API 명세 §4.9 표). 정렬 키는 저장 값이 아니라 화면에 보이는 점 위치(placement.displayed)다(FOCUS-015).
 * displayed가 null이면 미분류로 보고 좌표 정렬에서 뒤로 보낸다. 사분면 분류(단위 5) 전에는 모든 행동이 미분류라
 * 좌표 정렬 세 가지가 id 오름차순과 같다. 정렬은 응답 순서만 바꾸고 저장 값은 바꾸지 않는다.
 */
public final class ActionSorter {

	private ActionSorter() {
	}

	/** 원본을 바꾸지 않고 정렬 방식에 맞게 정렬한 새 목록을 돌려준다. */
	public static List<ActionResponse> sort(List<ActionResponse> items, ActionSort sort) {
		List<ActionResponse> sorted = new ArrayList<>(items);
		sorted.sort(comparator(sort));
		return sorted;
	}

	private static Comparator<ActionResponse> comparator(ActionSort sort) {
		Comparator<ActionResponse> byId = Comparator.comparingLong(ActionResponse::id);
		return switch (sort) {
			// x+y 내림차순 -> x 내림차순 -> id 오름차순. 미분류는 뒤.
			case IMPORTANCE_URGENCY -> unclassifiedLast(
					Comparator.<ActionResponse>comparingInt(a -> a.placement().displayed().importance()
							+ a.placement().displayed().urgency()).reversed()
							.thenComparing(Comparator.<ActionResponse>comparingInt(
									a -> a.placement().displayed().importance()).reversed()))
					.thenComparing(byId);
			case IMPORTANCE -> unclassifiedLast(descending(a -> a.placement().displayed().importance()))
					.thenComparing(byId);
			case URGENCY -> unclassifiedLast(descending(a -> a.placement().displayed().urgency()))
					.thenComparing(byId);
			// GOAL 먼저, 그다음 MAINTENANCE. 각 그룹 안은 id 오름차순.
			case GROUP -> Comparator.<ActionResponse>comparingInt(a -> a.type() == ActionType.GOAL ? 0 : 1)
					.thenComparing(byId);
		};
	}

	private static Comparator<ActionResponse> descending(ToIntFunction<ActionResponse> key) {
		return Comparator.<ActionResponse>comparingInt(key).reversed();
	}

	/** displayed가 없는(미분류) 행동을 모두 뒤로 보내고, 둘 다 배치된 경우에만 키 비교자를 쓴다. 미분류끼리는 같다고 본다. */
	private static Comparator<ActionResponse> unclassifiedLast(Comparator<ActionResponse> keyed) {
		return (a, b) -> {
			boolean aPlaced = a.placement().displayed() != null;
			boolean bPlaced = b.placement().displayed() != null;
			if (aPlaced && bPlaced) {
				return keyed.compare(a, b);
			}
			return aPlaced == bPlaced ? 0 : (aPlaced ? -1 : 1);
		};
	}
}
