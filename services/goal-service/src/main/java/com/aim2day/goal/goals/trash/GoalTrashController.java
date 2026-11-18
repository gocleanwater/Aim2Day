package com.aim2day.goal.goals.trash;

import com.aim2day.goal.common.response.ApiResponse;
import com.aim2day.goal.goals.trash.dto.TrashDtos.DeleteGoalResponse;
import com.aim2day.goal.goals.trash.dto.TrashDtos.TrashItemResponse;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 목표 삭제·휴지통·복원 API(§4.7). 모든 메서드가 세션의 userId를 서비스에 넘기고, 서비스가 소유권을 확인한다. */
@RestController
@RequestMapping("/api/goals")
public class GoalTrashController {

	private final GoalTrashService service;

	public GoalTrashController(GoalTrashService service) {
		this.service = service;
	}

	@GetMapping("/trash")
	public ApiResponse<List<TrashItemResponse>> trash(@AuthenticationPrincipal Long userId) {
		return ApiResponse.success(service.trash(userId));
	}

	@DeleteMapping("/{level}/{id}")
	public ApiResponse<DeleteGoalResponse> delete(@AuthenticationPrincipal Long userId, @PathVariable String level,
			@PathVariable long id, @RequestParam(required = false) Boolean confirm) {
		return ApiResponse.success(service.delete(userId, GoalLevel.fromPath(level, id), id,
				Boolean.TRUE.equals(confirm)));
	}

	@PostMapping("/{level}/{id}/restore")
	public ApiResponse<Object> restore(@AuthenticationPrincipal Long userId, @PathVariable String level,
			@PathVariable long id) {
		return ApiResponse.success(service.restore(userId, GoalLevel.fromPath(level, id), id));
	}
}
