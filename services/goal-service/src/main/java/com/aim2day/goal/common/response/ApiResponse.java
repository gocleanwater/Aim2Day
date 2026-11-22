package com.aim2day.goal.common.response;

/**
 * 성공 응답 본문. API 명세 §2.1의 {code, message, data} 모양 그대로다.
 * 성공 code는 항상 SUCCESS다. 서비스끼리 라이브러리를 공유하지 않으므로(공유 계약 §5)
 * user-service도 같은 모양의 클래스를 따로 가진다.
 */
public record ApiResponse<T>(String code, String message, T data) {

	public static final String SUCCESS_CODE = "SUCCESS";
	public static final String SUCCESS_MESSAGE = "요청이 처리되었습니다.";

	/** 데이터를 담은 성공 응답을 만든다. code는 SUCCESS, message는 고정 문구다. */
	public static <T> ApiResponse<T> success(T data) {
		return new ApiResponse<>(SUCCESS_CODE, SUCCESS_MESSAGE, data);
	}

	/** 반환할 데이터가 없는 성공(삭제 등). data는 null로 나간다. */
	public static ApiResponse<Void> success() {
		return new ApiResponse<>(SUCCESS_CODE, SUCCESS_MESSAGE, null);
	}
}
