package com.aim2day.user.mail;

import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

/** JavaMailSender(SMTP)로 실제 메일을 보낸다. 보내는 주소는 spring.mail.username이다. */
public class SmtpAuthMailSender implements AuthMailSender {

	static final String SIGNUP_CODE_SUBJECT = "[Aim2Day] 이메일 인증번호 안내";

	static final String PASSWORD_RESET_SUBJECT = "[Aim2Day] 비밀번호 재설정 안내";

	private final JavaMailSender mailSender;
	private final String from;

	public SmtpAuthMailSender(JavaMailSender mailSender, String from) {
		this.mailSender = mailSender;
		this.from = from;
	}

	@Override
	public void sendSignupCode(String to, String code) {
		send(to, SIGNUP_CODE_SUBJECT, "Aim2Day 회원가입 인증번호입니다.\n\n"
				+ "인증번호: " + code + "\n\n"
				+ "인증번호는 10분 동안 유효합니다. 가입 화면에 입력해 이메일 인증을 완료해 주세요.\n"
				+ "본인이 요청하지 않았다면 이 메일을 무시해 주세요.");
	}

	@Override
	public void sendPasswordResetLink(String to, String link) {
		send(to, PASSWORD_RESET_SUBJECT, "Aim2Day 비밀번호 재설정 링크입니다.\n\n"
				+ link + "\n\n"
				+ "링크는 30분 동안 유효하며 한 번만 사용할 수 있습니다. 새 링크를 요청하면 이전 링크는 사용할 수 없습니다.\n"
				+ "본인이 요청하지 않았다면 이 메일을 무시해 주세요. 비밀번호는 바뀌지 않습니다.");
	}

	private void send(String to, String subject, String text) {
		SimpleMailMessage message = new SimpleMailMessage();
		if (from != null && !from.isBlank()) {
			message.setFrom(from);
		}
		message.setTo(to);
		message.setSubject(subject);
		message.setText(text);
		try {
			mailSender.send(message);
		}
		catch (MailException e) {
			// 원인 예외 메시지에 수신 주소 등이 들어갈 수 있어 호출 측은 로그에 클래스명만 남긴다.
			throw new MailDeliveryException("메일 전송 실패", e);
		}
	}
}
