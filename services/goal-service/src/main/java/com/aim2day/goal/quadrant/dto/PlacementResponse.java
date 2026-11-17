package com.aim2day.goal.quadrant.dto;

import com.aim2day.goal.actions.dto.ActionResponse.Placement;

/** PUT placement, DELETE placement/pending 응답(API 명세 §4.10). */
public record PlacementResponse(long actionId, Placement placement) {
}
