package com.aim2day.goal.calendar;

import com.aim2day.goal.calendar.dto.CalendarDtos.CalendarCompletionRequest;
import com.aim2day.goal.calendar.dto.CalendarDtos.CalendarDashboardResponse;
import com.aim2day.goal.calendar.dto.CalendarDtos.CalendarItemResponse;
import com.aim2day.goal.calendar.dto.CalendarDtos.CreateCalendarItemRequest;
import com.aim2day.goal.calendar.dto.CalendarDtos.UpdateCalendarItemRequest;
import com.aim2day.goal.common.response.ApiResponse;
import java.time.LocalDate;
import java.util.List;
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

/** 달력 API(§4.11). 세션의 userId를 서비스에 넘기고, 서비스가 소유권을 확인한다. */
@RestController
@RequestMapping("/api/calendar")
public class CalendarController {

	private final CalendarItemService service;
	private final CalendarQueryService queryService;

	public CalendarController(CalendarItemService service, CalendarQueryService queryService) {
		this.service = service;
		this.queryService = queryService;
	}

	@GetMapping("/items")
	public ApiResponse<List<CalendarItemResponse>> list(@AuthenticationPrincipal Long userId,
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
			@RequestParam(required = false) List<Long> yearlyGoalIds,
			@RequestParam(defaultValue = "true") boolean includeUnlinked) {
		return ApiResponse.success(queryService.range(userId, from, to, yearlyGoalIds, includeUnlinked));
	}

	@PostMapping("/items")
	@ResponseStatus(HttpStatus.CREATED)
	public ApiResponse<CalendarItemResponse> create(@AuthenticationPrincipal Long userId,
			@RequestBody CreateCalendarItemRequest request) {
		return ApiResponse.success(service.create(userId, request));
	}

	@PatchMapping("/items/{id}")
	public ApiResponse<CalendarItemResponse> update(@AuthenticationPrincipal Long userId, @PathVariable long id,
			@RequestBody UpdateCalendarItemRequest request) {
		return ApiResponse.success(service.update(userId, id, request));
	}

	@PutMapping("/items/{id}/completion")
	public ApiResponse<CalendarItemResponse> complete(@AuthenticationPrincipal Long userId, @PathVariable long id,
			@RequestBody CalendarCompletionRequest request) {
		return ApiResponse.success(service.complete(userId, id, request));
	}

	@DeleteMapping("/items/{id}")
	public ApiResponse<Void> delete(@AuthenticationPrincipal Long userId, @PathVariable long id) {
		service.delete(userId, id);
		return ApiResponse.success();
	}

	@GetMapping("/dashboard")
	public ApiResponse<CalendarDashboardResponse> dashboard(@AuthenticationPrincipal Long userId) {
		return ApiResponse.success(queryService.dashboard(userId));
	}
}
