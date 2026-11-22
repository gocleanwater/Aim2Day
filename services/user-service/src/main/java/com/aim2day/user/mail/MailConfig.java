package com.aim2day.user.mail;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * 메일 발송기 선택. spring.mail.host가 있으면 SMTP, 없으면 로그 구현이다.
 * JavaMailSender 빈 유무로 고르면 자동 설정 순서에 기대게 되므로, 자동 설정이 JavaMailSender를 만드는
 * 조건과 같은 프로퍼티(spring.mail.host)로 직접 고른다. 두 빈은 같은 클래스 안에서 선언 순서대로
 * 평가되므로 로그 구현의 @ConditionalOnMissingBean은 SMTP 빈이 등록된 뒤에 판단된다.
 */
@Configuration
public class MailConfig {

	/** SMTP 발송기. spring.mail.host가 설정된 운영 환경에서만 등록되며, 발신 주소는 spring.mail.username이다. */
	@Bean
	@ConditionalOnProperty(prefix = "spring.mail", name = "host")
	AuthMailSender smtpAuthMailSender(JavaMailSender javaMailSender,
			@Value("${spring.mail.username:}") String from) {
		return new SmtpAuthMailSender(javaMailSender, from);
	}

	/** 대체 발송기. SMTP 발송기가 없을 때만 등록되어 메일 설정 없이도 서비스가 기동·재현된다. */
	@Bean
	@ConditionalOnMissingBean(AuthMailSender.class)
	AuthMailSender logAuthMailSender() {
		return new LogAuthMailSender();
	}
}
