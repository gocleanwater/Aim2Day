package com.aim2day.goal.common.web;

import com.aim2day.goal.common.exception.CommonErrorCode;
import com.aim2day.goal.common.exception.GlobalExceptionHandler;
import com.aim2day.goal.common.response.ErrorResponse;
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
 * Boot 기본 BasicErrorController는 {timestamp,status,error,path} 형식이라 명세와 다르다.
 */
@Hidden
@RestController
public class ApiErrorController implements ErrorController {

	/** 서블릿 /error로 넘어온 오류를 공통 오류 JSON으로 바꾼다. 4xx는 상태에 맞는 코드로, 그 밖은 500 INTERNAL_ERROR로 응답한다. */
	@RequestMapping("/error")
	public ResponseEntity<ErrorResponse> error(HttpServletRequest request) {
		Object attr = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
		HttpStatus status = attr instanceof Integer code && HttpStatus.resolve(code) != null
				? HttpStatus.valueOf(code) : HttpStatus.INTERNAL_SERVER_ERROR;
		if (status.is4xxClientError()) {
			return GlobalExceptionHandler.fromClientStatus(status);
		}
		return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
				.body(ErrorResponse.of(CommonErrorCode.INTERNAL_ERROR));
	}
}
