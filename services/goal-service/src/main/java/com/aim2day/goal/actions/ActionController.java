package com.aim2day.goal.actions;

import com.aim2day.goal.actions.dto.ActionCompletionResponse;
import com.aim2day.goal.actions.dto.ActionListResponse;
import com.aim2day.goal.actions.dto.ActionResponse;
import com.aim2day.goal.actions.dto.CompletionRequest;
import com.aim2day.goal.actions.dto.CreateActionRequest;
import com.aim2day.goal.actions.dto.UpdateActionRequest;
import com.aim2day.goal.common.response.ApiResponse;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 행동 API(§4.9, 사분면 분류 제외). 세션의 userId를 서비스에 넘기고, 서비스가 소유권을 확인한다. */
@RestController
@RequestMapping("/api/actions")
public class ActionController {

	private final ActionService service;
	private final ActionListService listService;

	public ActionController(ActionService service, ActionListService listService) {
		this.service = service;
		this.listService = listService;
	}

	@GetMapping
	public ApiResponse<ActionListResponse> list(@AuthenticationPrincipal Long userId,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
			@RequestParam(required = false) ActionSort sort) {
		return ApiResponse.success(listService.list(userId, date, sort));
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public ApiResponse<ActionResponse> create(@AuthenticationPrincipal Long userId,
			@RequestBody CreateActionRequest request) {
		return ApiResponse.success(service.create(userId, request));
	}

	@PatchMapping("/{id}")
	public ApiResponse<ActionResponse> update(@AuthenticationPrincipal Long userId, @PathVariable long id,
			@RequestBody UpdateActionRequest request) {
		return ApiResponse.success(service.update(userId, id, request));
	}

	@PutMapping("/{id}/completion")
	public ApiResponse<ActionCompletionResponse> complete(@AuthenticationPrincipal Long userId,
			@PathVariable long id, @RequestBody CompletionRequest request) {
		return ApiResponse.success(service.complete(userId, id, request));
	}

	@DeleteMapping("/{id}")
	public ApiResponse<Void> delete(@AuthenticationPrincipal Long userId, @PathVariable long id) {
		service.delete(userId, id);
		return ApiResponse.success();
	}
}
