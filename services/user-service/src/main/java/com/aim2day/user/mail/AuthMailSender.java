package com.aim2day.user.mail;

/** 인증 관련 메일 발송기. 구현은 SMTP와 로그 두 가지이며 spring.mail.host 유무로 고른다(MailConfig). */
public interface AuthMailSender {

	/**
	 * 가입 인증번호(6자리)를 보낸다.
	 *
	 * @throws MailDeliveryException 전송에 실패했을 때
	 */
	void sendSignupCode(String to, String code);
}
