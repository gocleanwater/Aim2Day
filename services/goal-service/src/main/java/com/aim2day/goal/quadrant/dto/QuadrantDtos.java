package com.aim2day.goal.quadrant.dto;

import com.aim2day.goal.actions.dto.ActionDtos.ActionResponse.Placement;
import com.fasterxml.jackson.annotation.JsonSetter;
import java.time.LocalDate;

/** 이 도메인의 요청·응답 형식 모음. */
public final class QuadrantDtos {

	private QuadrantDtos() {
	}

	/** POST placements/confirm 응답(API 명세 §4.10). */
	public record ConfirmResponse(LocalDate date, int confirmedCount, int unclassifiedCount) {
	}

	/**
	 * PUT placement 본문. 두 필드는 항상 함께 보내야 하고, 필드가 빠진 것과 명시적 null(트레이로 되돌리기)이 다른 뜻이라
	 * 세터가 불렸는지를 플래그로 구분한다. 값은 Object로 받아 서비스가 정수 여부·범위를 직접 검사한다
	 * (72.5나 "abc"가 400 MALFORMED_REQUEST가 아니라 필드 단위 VALIDATION_FAILED가 되게 한다).
	 */
	public static class PlacementRequest {

		private Object importance;
		private Object urgency;
		private boolean importancePresent;
		private boolean urgencyPresent;

		public Object importance() {
			return importance;
		}

		public Object urgency() {
			return urgency;
		}

		public boolean importancePresent() {
			return importancePresent;
		}

		public boolean urgencyPresent() {
			return urgencyPresent;
		}

		@JsonSetter("importance")
		public void setImportance(Object importance) {
			this.importance = importance;
			this.importancePresent = true;
		}

		@JsonSetter("urgency")
		public void setUrgency(Object urgency) {
			this.urgency = urgency;
			this.urgencyPresent = true;
		}
	}

	/** PUT placement, DELETE placement/pending 응답(API 명세 §4.10). */
	public record PlacementResponse(long actionId, Placement placement) {
	}
}
