package com.aim2day.goal.reports;

import com.aim2day.goal.common.response.ApiResponse;
import com.aim2day.goal.reports.dto.ReportDtos.PutReviewRequest;
import com.aim2day.goal.reports.dto.ReportDtos.ReportResponse;
import com.aim2day.goal.reports.dto.ReportDtos.ReportTabsResponse;
import com.aim2day.goal.reports.dto.ReportDtos.ReviewResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 리포트·직접 회고 API(§4.12). 경로에 소유자 식별자가 없고 항상 세션의 userId로 조회하므로
 * 다른 사용자의 데이터에는 접근할 수 없다. periodType·period·month는 서비스가 문자열로 받아 직접 파싱한다
 * (형식 오류를 일관되게 400 VALIDATION_FAILED로 내기 위해서다).
 */
@RestController
@RequestMapping("/api/reports")
public class ReportController {

	private final ReportService reportService;
	private final PeriodReviewService reviewService;

	public ReportController(ReportService reportService, PeriodReviewService reviewService) {
		this.reportService = reportService;
		this.reviewService = reviewService;
	}

	/**
	 * GET /api/reports/tabs?month=2026-12: 기준 월에 끝나는 기간(주·월·분기·연)의 리포트 탭을 돌려준다(결정 12, 20).
	 * 요청값: month(YYYY-MM, 생략하면 사용자 기준 이번 달).
	 * 응답: 200과 탭 목록. 형식 오류는 400 VALIDATION_FAILED.
	 */
	@GetMapping("/tabs")
	public ApiResponse<ReportTabsResponse> tabs(@AuthenticationPrincipal Long userId,
			@RequestParam(required = false) String month) {
		return ApiResponse.success(reportService.tabs(userId, month));
	}

	/**
	 * GET /api/reports?periodType=MONTHLY&period=2026-11: 한 기간의 리포트(목표 진행률·행동 집계·회고)를 계산해 돌려준다.
	 * 요청값: periodType(WEEKLY·MONTHLY·QUARTERLY·YEARLY), period(유형별 형식).
	 * 응답: 200과 ReportResponse. 형식 오류는 400 VALIDATION_FAILED, 조회할 수 없는 기간은 422 REPORT_INVALID_PERIOD.
	 */
	@GetMapping
	public ApiResponse<ReportResponse> report(@AuthenticationPrincipal Long userId, @RequestParam String periodType,
			@RequestParam String period) {
		return ApiResponse.success(reportService.report(userId, periodType, period));
	}

	/**
	 * PUT /api/reports/reviews/{periodType}/{period}: 기간별 직접 회고를 저장한다(없으면 만들고 있으면 덮어쓴다).
	 * 요청값: improvements·nextFocus·version을 담은 PutReviewRequest.
	 * 응답: 200과 저장된 ReviewResponse. 입력 오류는 400, 아직 오지 않은 기간은 422 REPORT_INVALID_PERIOD, 동시 수정은 409 VERSION_CONFLICT.
	 */
	@PutMapping("/reviews/{periodType}/{period}")
	public ApiResponse<ReviewResponse> putReview(@AuthenticationPrincipal Long userId, @PathVariable String periodType,
			@PathVariable String period, @RequestBody PutReviewRequest request) {
		return ApiResponse.success(reviewService.put(userId, periodType, period, request));
	}
}
