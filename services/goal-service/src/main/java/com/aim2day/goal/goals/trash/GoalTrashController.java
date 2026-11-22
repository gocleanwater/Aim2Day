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

	/**
	 * GET /api/goals/trash: 삭제 후 30일 이내인 목표(연·월·주간)를 휴지통 목록으로 돌려준다.
	 * 응답: 200과 삭제된 목표 목록(복구 기한 포함).
	 */
	@GetMapping("/trash")
	public ApiResponse<List<TrashItemResponse>> trash(@AuthenticationPrincipal Long userId) {
		return ApiResponse.success(service.trash(userId));
	}

	/**
	 * DELETE /api/goals/{level}/{id}?confirm=true: 목표를 소프트 삭제한다. level은 yearly·monthly·weekly.
	 * 요청값: confirm 쿼리(하위 목표·미완료 행동이 함께 숨겨지는 것을 확인했는지).
	 * 처리: 하위 항목이 있는데 confirm이 true가 아니면 삭제하지 않고 확인을 요구한다. 삭제는 복원 가능하도록 표시만 한다.
	 * 응답: 200과 삭제 결과. 확인 누락은 409 GOAL_DELETE_CONFIRM_REQUIRED, 잘못된 level이나 없는 목표는 404 RESOURCE_NOT_FOUND.
	 */
	@DeleteMapping("/{level}/{id}")
	public ApiResponse<DeleteGoalResponse> delete(@AuthenticationPrincipal Long userId, @PathVariable String level,
			@PathVariable long id, @RequestParam(required = false) Boolean confirm) {
		return ApiResponse.success(service.delete(userId, GoalLevel.fromPath(level, id), id,
				Boolean.TRUE.equals(confirm)));
	}

	/**
	 * POST /api/goals/{level}/{id}/restore: 휴지통의 목표를 복원한다.
	 * 처리: 상위 목표가 삭제 상태가 아니고 복구 기한(30일) 안일 때만 복원한다.
	 * 응답: 200과 복원된 목표. 상위가 삭제 상태면 409 GOAL_PARENT_DELETED, 기한 초과는 409 GOAL_RESTORE_EXPIRED, 없으면 404 RESOURCE_NOT_FOUND.
	 */
	@PostMapping("/{level}/{id}/restore")
	public ApiResponse<Object> restore(@AuthenticationPrincipal Long userId, @PathVariable String level,
			@PathVariable long id) {
		return ApiResponse.success(service.restore(userId, GoalLevel.fromPath(level, id), id));
	}
}
