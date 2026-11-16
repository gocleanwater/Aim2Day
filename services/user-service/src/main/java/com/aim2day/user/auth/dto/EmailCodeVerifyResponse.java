package com.aim2day.user.auth.dto;

public record EmailCodeVerifyResponse(String signupToken, int expiresInSeconds) {
}
