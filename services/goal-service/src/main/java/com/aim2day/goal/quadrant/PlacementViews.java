package com.aim2day.goal.quadrant;

import com.aim2day.goal.actions.dto.ActionDtos.ActionResponse.Coordinate;
import com.aim2day.goal.actions.dto.ActionDtos.ActionResponse.Placement;

/**
 * 좌표 행 -> 응답의 placement(API 명세 §4.9 표). 읽는 규칙이 한 곳에만 있어 모든 인스턴스가 같은 결과를 낸다.
 * 오늘이 아닌 날짜의 행은 확정 좌표만 쓴다. 미확정 좌표와 되돌리기 대기는 확정·취소할 방법이 없으므로 없는 것으로
 * 보고 DB 값은 지우지 않는다(결정 18).
 */
public final class PlacementViews {

	private PlacementViews() {
	}

	/** row가 null이면 미분류다. today는 "그 행의 classification_date가 오늘인가"다. */
	public static Placement of(QuadrantPlacement row, boolean today) {
		if (row == null) {
			return Placement.unclassified();
		}
		Coordinate confirmed = row.hasConfirmed()
				? new Coordinate(row.getConfirmedImportance(), row.getConfirmedUrgency()) : null;
		Coordinate pending = today && row.hasPending()
				? new Coordinate(row.getPendingImportance(), row.getPendingUrgency()) : null;
		boolean unclassifyPending = today && row.isPendingUnclassify();

		if (confirmed == null) {
			return pending == null ? Placement.unclassified() : new Placement("PENDING", null, pending, pending);
		}
		if (pending != null) {
			return new Placement("RECLASSIFYING", confirmed, pending, pending);
		}
		if (unclassifyPending) {
			return new Placement("PENDING_UNCLASSIFY", confirmed, null, null);
		}
		return new Placement("CONFIRMED", confirmed, null, confirmed);
	}

	/** 목록의 classification.unconfirmedChanges에 세는 상태인가(§4.9). */
	public static boolean isUnconfirmedChange(Placement p) {
		return switch (p.state()) {
			case "PENDING", "RECLASSIFYING", "PENDING_UNCLASSIFY" -> true;
			default -> false;
		};
	}
}
