package com.aim2day.goal.actions;

import com.aim2day.goal.actions.dto.ActionDtos.ActionCompletionResponse;
import com.aim2day.goal.actions.dto.ActionDtos.ActionListResponse;
import com.aim2day.goal.actions.dto.ActionDtos.ActionResponse;
import com.aim2day.goal.actions.dto.ActionDtos.CompletionRequest;
import com.aim2day.goal.actions.dto.ActionDtos.CreateActionRequest;
import com.aim2day.goal.actions.dto.ActionDtos.UpdateActionRequest;
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

	/**
	 * GET /api/actions: 특정 날짜의 행동 목록을 조회한다(§4.9).
	 * 요청값: date(ISO 날짜, 생략하면 사용자 기준 오늘), sort(정렬 방식, 생략하면 기본 정렬).
	 * 처리: 세션 userId 소유의 행동만 읽어 정렬하고, 지난 미완료 행동은 목록과 따로 담아 돌려준다(FOCUS-017).
	 * 응답: 200과 ActionListResponse. 날짜·정렬 값 형식 오류는 400 VALIDATION_FAILED.
	 */
	@GetMapping
	public ApiResponse<ActionListResponse> list(@AuthenticationPrincipal Long userId,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
			@RequestParam(required = false) ActionSort sort) {
		return ApiResponse.success(listService.list(userId, date, sort));
	}

	/**
	 * POST /api/actions: 행동을 만든다.
	 * 요청값: type(GOAL 또는 MAINTENANCE)·제목·수행일·마감일·연결 주간/월간 목표·기여값 등 CreateActionRequest(JSON 본문).
	 * 처리: 서비스가 입력을 검증하고, 목표에 연결하는 경우 현재 주 안의 날짜인지와 연결할 주간 목표를 판단해 저장한다.
	 * 응답: 201과 만들어진 행동. 입력 오류는 400 VALIDATION_FAILED, 주간 목표 없음·모호함은 422 ACTION_WEEKLY_GOAL_REQUIRED·ACTION_WEEKLY_GOAL_AMBIGUOUS,
	 * 날짜 범위 위반은 422 ACTION_DATE_OUT_OF_RANGE.
	 */
	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public ApiResponse<ActionResponse> create(@AuthenticationPrincipal Long userId,
			@RequestBody CreateActionRequest request) {
		return ApiResponse.success(service.create(userId, request));
	}

	/**
	 * PATCH /api/actions/{id}: 행동을 부분 수정한다.
	 * 요청값: 경로의 행동 id와 수정할 필드·version을 담은 UpdateActionRequest.
	 * 처리: 소유권을 확인하고 version을 검사한 뒤 수정한다. 연결 목표가 있는 행동은 필요하면 진행률도 함께 다시 계산한다.
	 * 응답: 200과 수정된 행동. 없거나 남의 행동이면 404 RESOURCE_NOT_FOUND, 동시 수정은 409 VERSION_CONFLICT,
	 * 규칙 위반은 422(ACTION_*).
	 */
	@PatchMapping("/{id}")
	public ApiResponse<ActionResponse> update(@AuthenticationPrincipal Long userId, @PathVariable long id,
			@RequestBody UpdateActionRequest request) {
		return ApiResponse.success(service.update(userId, id, request));
	}

	/**
	 * PUT /api/actions/{id}/completion: 행동의 완료 여부를 지정한다(멱등).
	 * 요청값: 경로의 행동 id, 본문의 completed(필수)와 memo(선택).
	 * 처리: 소유권을 확인하고 행 잠금을 잡은 뒤 완료 상태를 바꾼다. 이미 같은 상태면 아무것도 바꾸지 않는다.
수치형 주간 목표에 연결된 행동이면 수치 기록과 현재값도, 같은 트랜잭션에서 주간·월간 진행률까지 다시 계산한다.
	 * 응답: 200과 완료 결과(행동과 갱신된 진행률). 수행일이 오늘보다 뒤면 422 ACTION_FUTURE_COMPLETION,
	 * completed가 없으면 400 VALIDATION_FAILED, 없거나 남의 행동이면 404 RESOURCE_NOT_FOUND.
	 */
	@PutMapping("/{id}/completion")
	public ApiResponse<ActionCompletionResponse> complete(@AuthenticationPrincipal Long userId,
			@PathVariable long id, @RequestBody CompletionRequest request) {
		return ApiResponse.success(service.complete(userId, id, request));
	}

	/**
	 * DELETE /api/actions/{id}: 행동을 삭제한다.
	 * 처리: 소유권을 확인한 뒤 소프트 삭제한다. 미완료 행동이면 체크형 진행률 분모가 바뀌므로 다시 계산하고,
완료된 행동은 목록에서만 숨기고 기록·진행률은 그대로 둔다. 본문 없이 성공 응답만 돌려준다.
	 * 응답: 200. 없거나 남의 행동이면 404 RESOURCE_NOT_FOUND.
	 */
	@DeleteMapping("/{id}")
	public ApiResponse<Void> delete(@AuthenticationPrincipal Long userId, @PathVariable long id) {
		service.delete(userId, id);
		return ApiResponse.success();
	}
}
