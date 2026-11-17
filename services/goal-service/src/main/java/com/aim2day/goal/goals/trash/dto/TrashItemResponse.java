package com.aim2day.goal.goals.trash.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;

/** 휴지통 항목(API 명세 §4.7). 연간 목표의 parent는 null이다. */
public record TrashItemResponse(String level, long id, String title, String periodLabel, Instant deletedAt,
		Instant restoreDeadline, long remainingDays, Parent parent,
		@JsonProperty("isParentDeleted") boolean isParentDeleted) {

	public record Parent(String level, long id, String title) {
	}
}
