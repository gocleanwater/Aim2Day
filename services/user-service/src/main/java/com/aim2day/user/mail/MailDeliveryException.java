package com.aim2day.user.mail;

/** 메일 전송 실패. SMTP 라이브러리 예외를 감싸 서비스 계층이 구현 방식을 모르게 한다. */
public class MailDeliveryException extends RuntimeException {

	/** 서비스 계층이 502 AUTH_MAIL_SEND_FAILED로 바꿀 수 있도록 원인 예외와 함께 만든다. */
	public MailDeliveryException(String message, Throwable cause) {
		super(message, cause);
	}
}
