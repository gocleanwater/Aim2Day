package com.aim2day.user.auth;

import com.aim2day.user.auth.dto.EmailCodeRequest;
import com.aim2day.user.auth.dto.EmailCodeResponse;
import com.aim2day.user.auth.dto.EmailCodeVerifyRequest;
import com.aim2day.user.auth.dto.EmailCodeVerifyResponse;
import com.aim2day.user.auth.dto.SignupRequest;
import com.aim2day.user.auth.dto.SignupResponse;
import com.aim2day.user.common.response.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

	private final EmailCodeService emailCodeService;
	private final SignupService signupService;

	public AuthController(EmailCodeService emailCodeService, SignupService signupService) {
		this.emailCodeService = emailCodeService;
		this.signupService = signupService;
	}

	@PostMapping("/email-codes")
	public ApiResponse<EmailCodeResponse> requestCode(@Valid @RequestBody EmailCodeRequest request) {
		return ApiResponse.success(emailCodeService.requestCode(request.email()));
	}

	@PostMapping("/email-codes/verify")
	public ApiResponse<EmailCodeVerifyResponse> verifyCode(@Valid @RequestBody EmailCodeVerifyRequest request) {
		return ApiResponse.success(emailCodeService.verifyCode(request.email(), request.code()));
	}

	@PostMapping("/signup")
	@ResponseStatus(HttpStatus.CREATED)
	public ApiResponse<SignupResponse> signup(@Valid @RequestBody SignupRequest request) {
		return ApiResponse.success(signupService.signup(request));
	}
}
