package com.aim2day.user.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SMTP 설정이 없을 때 쓰는 대체 발송기(공유 계약 §2). 메일을 보내지 않고 마스킹한 받는 주소와 인증번호를 INFO 로그로
 * 남긴 뒤 성공으로 처리한다. SMTP 키 없이도 제출물을 재현하게 하려는 것이며, 인증번호를 로그에 남기는 것은
 * 이 모드에서만 허용된다(운영 SMTP 모드에서는 번호를 로그에 남기지 않는다). 이메일 원문은 이 모드에서도 남기지 않는다.
 */
public class LogAuthMailSender implements AuthMailSender {

	private static final Logger log = LoggerFactory.getLogger(LogAuthMailSender.class);

	@Override
	public void sendSignupCode(String to, String code) {
		log.info("[MAIL] to={} purpose=signup-code code={}", MailAddressMasker.mask(to), code);
	}

	/** 링크에는 재설정 토큰 원문이 들어 있다. 메일을 실제로 보내지 않는 이 모드에서만 로그에 남긴다(SMTP 모드는 남기지 않는다). */
	@Override
	public void sendPasswordResetLink(String to, String link) {
		log.info("[MAIL] to={} purpose=password-reset link={}", MailAddressMasker.mask(to), link);
	}
}
