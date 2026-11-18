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

	@GetMapping("/tabs")
	public ApiResponse<ReportTabsResponse> tabs(@AuthenticationPrincipal Long userId,
			@RequestParam(required = false) String month) {
		return ApiResponse.success(reportService.tabs(userId, month));
	}

	@GetMapping
	public ApiResponse<ReportResponse> report(@AuthenticationPrincipal Long userId, @RequestParam String periodType,
			@RequestParam String period) {
		return ApiResponse.success(reportService.report(userId, periodType, period));
	}

	@PutMapping("/reviews/{periodType}/{period}")
	public ApiResponse<ReviewResponse> putReview(@AuthenticationPrincipal Long userId, @PathVariable String periodType,
			@PathVariable String period, @RequestBody PutReviewRequest request) {
		return ApiResponse.success(reviewService.put(userId, periodType, period, request));
	}
}
