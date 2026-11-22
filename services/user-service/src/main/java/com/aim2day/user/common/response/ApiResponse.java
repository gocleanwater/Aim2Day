package com.aim2day.user.common.response;

/**
 * 성공 응답 본문 {code, message, data} (API 명세 §2.1).
 * 성공 code는 항상 SUCCESS라서 정적 팩토리로만 만든다.
 */
public record ApiResponse<T>(String code, String message, T data) {

	public static final String SUCCESS_CODE = "SUCCESS";
	public static final String SUCCESS_MESSAGE = "요청이 처리되었습니다.";

	/** 데이터를 담은 성공 응답을 만든다. code=SUCCESS, message는 고정 문구다. */
	public static <T> ApiResponse<T> success(T data) {
		return new ApiResponse<>(SUCCESS_CODE, SUCCESS_MESSAGE, data);
	}

	/** 반환할 데이터가 없는 성공(예: GET /api/auth/csrf). data는 null로 직렬화된다. */
	public static ApiResponse<Void> success() {
		return new ApiResponse<>(SUCCESS_CODE, SUCCESS_MESSAGE, null);
	}
}
