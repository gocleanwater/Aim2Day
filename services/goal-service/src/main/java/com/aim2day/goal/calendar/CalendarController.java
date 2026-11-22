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

	/**
	 * GET /api/calendar/items: 기간 안의 달력 항목을 조회한다(§4.11).
	 * 요청값: from·to(ISO 날짜, 필수, 최대 100일), yearlyGoalIds(연결 연간 목표 필터, 선택), includeUnlinked("목표 없음"으로 보이는 항목 포함 여부, 기본 true).
	 * 처리: 세션 userId 소유의 항목만 범위 조회 서비스에서 읽는다.
	 * 응답: 200과 달력 항목 목록. 날짜 형식 오류는 400 VALIDATION_FAILED, 기간이 거꾸로이거나 100일을 넘으면 422 REPORT_INVALID_PERIOD, 미로그인은 401 UNAUTHENTICATED.
	 */
	@GetMapping("/items")
	public ApiResponse<List<CalendarItemResponse>> list(@AuthenticationPrincipal Long userId,
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
			@RequestParam(required = false) List<Long> yearlyGoalIds,
			@RequestParam(defaultValue = "true") boolean includeUnlinked) {
		return ApiResponse.success(queryService.range(userId, from, to, yearlyGoalIds, includeUnlinked));
	}

	/**
	 * POST /api/calendar/items: 달력 항목(해야 할 일 TASK 또는 일정 EVENT)을 만든다.
	 * 요청값: itemType·제목·설명·startDate·endDate·연결 연간 목표 id 등 CreateCalendarItemRequest(JSON 본문).
	 * TASK는 종료일이 없거나 시작일과 같아야 하고, EVENT는 종료일이 시작일 이상이어야 한다.
	 * 처리: 서비스가 입력을 검증하고, 사용자 행 잠금 아래에서 연결 연간 목표가 내 삭제되지 않은 목표인지 확인한 뒤 저장한다.
	 * 응답: 201과 만들어진 항목. 입력 오류는 400 VALIDATION_FAILED, 상위 목표 사용 불가는 422 GOAL_PARENT_UNAVAILABLE.
	 */
	@PostMapping("/items")
	@ResponseStatus(HttpStatus.CREATED)
	public ApiResponse<CalendarItemResponse> create(@AuthenticationPrincipal Long userId,
			@RequestBody CreateCalendarItemRequest request) {
		return ApiResponse.success(service.create(userId, request));
	}

	/**
	 * PATCH /api/calendar/items/{id}: 달력 항목을 부분 수정한다.
	 * 요청값: 경로의 항목 id와 수정할 필드·version을 담은 UpdateCalendarItemRequest.
	 * 처리: 서비스가 user_id 조건으로 항목을 찾아 소유권을 확인하고, version이 다르면 거부한다.
	 * 응답: 200과 수정된 항목. 없거나 남의 항목이면 404 RESOURCE_NOT_FOUND, 동시 수정은 409 VERSION_CONFLICT,
	 * 종류 변경 시도는 422 CALENDAR_ITEM_TYPE_IMMUTABLE.
	 */
	@PatchMapping("/items/{id}")
	public ApiResponse<CalendarItemResponse> update(@AuthenticationPrincipal Long userId, @PathVariable long id,
			@RequestBody UpdateCalendarItemRequest request) {
		return ApiResponse.success(service.update(userId, id, request));
	}

	/**
	 * PUT /api/calendar/items/{id}/completion: 달력 항목의 완료 여부를 지정한다(멱등: 같은 값을 다시 보내도 결과가 같다).
	 * 요청값: 경로의 항목 id, 본문의 completed 값.
	 * 처리: 소유권을 확인한 뒤 완료 상태와 완료 시각을 갱신한다.
	 * 응답: 200과 갱신된 항목. completed가 없으면 400 VALIDATION_FAILED, 없거나 남의 항목이면 404 RESOURCE_NOT_FOUND.
	 */
	@PutMapping("/items/{id}/completion")
	public ApiResponse<CalendarItemResponse> complete(@AuthenticationPrincipal Long userId, @PathVariable long id,
			@RequestBody CalendarCompletionRequest request) {
		return ApiResponse.success(service.complete(userId, id, request));
	}

	/**
	 * DELETE /api/calendar/items/{id}: 달력 항목을 삭제한다.
	 * 처리: 소유권을 확인한 뒤 삭제한다. 본문 없이 성공 응답만 돌려준다.
	 * 응답: 200. 없거나 남의 항목이면 404 RESOURCE_NOT_FOUND.
	 */
	@DeleteMapping("/items/{id}")
	public ApiResponse<Void> delete(@AuthenticationPrincipal Long userId, @PathVariable long id) {
		service.delete(userId, id);
		return ApiResponse.success();
	}

	/**
	 * GET /api/calendar/dashboard: 대시보드에 쓸 해야 할 일·다가오는 일정·지난 일정을 조회한다.
	 * 처리: 미완료 TASK 전체, 앞으로 7일 안의 EVENT, 지난 7일 안에 끝난 EVENT를 세션 userId 기준으로 읽는다.
	 * 응답: 200과 CalendarDashboardResponse.
	 */
	@GetMapping("/dashboard")
	public ApiResponse<CalendarDashboardResponse> dashboard(@AuthenticationPrincipal Long userId) {
		return ApiResponse.success(queryService.dashboard(userId));
	}
}
