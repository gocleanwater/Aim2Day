package com.aim2day.user.support;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** 필터 단계 예외를 재현하는 테스트 전용 필터. 컨트롤러 밖이라 서블릿 /error 디스패치를 타게 된다. */
@Component
public class FailingTestFilter extends OncePerRequestFilter {

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		return !request.getRequestURI().equals("/api/auth/filter-boom");
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		throw new ServletException("secret-filter-detail");
	}
}
