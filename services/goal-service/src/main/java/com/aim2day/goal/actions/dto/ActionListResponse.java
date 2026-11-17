package com.aim2day.goal.actions.dto;

import com.aim2day.goal.actions.ActionSort;
import java.time.LocalDate;
import java.util.List;

/** 행동 목록 응답(API 명세 §4.9). pastIncomplete는 date가 오늘일 때만 채우고 아니면 null이다. */
public record ActionListResponse(LocalDate date, ActionSort sort, Summary summary, Classification classification,
		List<ActionResponse> items, PastIncomplete pastIncomplete) {

	public record Counts(int total, int completed) {
	}

	public record Summary(Counts goal, Counts maintenance) {
	}

	public record Classification(int unclassified, int unconfirmedChanges, int confirmed, boolean confirmedToday) {
	}

	public record PastIncomplete(int count, List<ActionResponse> items) {
	}
}
