package com.aim2day.user.common.web;

import com.aim2day.user.common.response.ErrorCode;
import com.aim2day.user.common.response.ErrorResponse;
import com.aim2day.user.common.response.GlobalExceptionHandler;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 컨트롤러 밖(필터, 컨테이너)에서 난 오류가 서블릿 /error로 전달될 때 공통 오류 JSON으로 응답한다.
 * Boot 기본 BasicErrorController는 {timestamp,status,error,path} 형식이라 명세와 다르고,
 * 내부 예외 문구가 실릴 수 있어 상태 코드만 보고 고정 문구로 응답한다.
 */
@Hidden
@RestController
public class ApiErrorController implements ErrorController {

	/**
	 * /error 디스패치 처리. 4xx는 상태에 대응하는 코드(404는 RESOURCE_NOT_FOUND, 그 외는 MALFORMED_REQUEST)로,
	 * 그 밖의 모든 상태는 500 INTERNAL_ERROR 고정 문구로 응답한다. 상태 속성이 없거나 알 수 없으면 500으로 본다.
	 */
	@RequestMapping("/error")
	public ResponseEntity<ErrorResponse> error(HttpServletRequest request) {
		Object attr = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
		HttpStatus status = attr instanceof Integer code && HttpStatus.resolve(code) != null
				? HttpStatus.valueOf(code) : HttpStatus.INTERNAL_SERVER_ERROR;
		if (status.is4xxClientError()) {
			return ResponseEntity.status(status).body(ErrorResponse.of(GlobalExceptionHandler.codeFor(status)));
		}
		return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
				.body(ErrorResponse.of(ErrorCode.INTERNAL_ERROR));
	}
}
