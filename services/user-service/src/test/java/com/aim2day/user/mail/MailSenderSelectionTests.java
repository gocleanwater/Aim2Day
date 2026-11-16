package com.aim2day.user.mail;

import static org.assertj.core.api.Assertions.assertThat;

import com.aim2day.user.support.TestSessionConfig;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/** spring.mail.host 유무에 따라 발송기 구현이 고르는지 확인한다(공유 계약 §2). */
class MailSenderSelectionTests {

	@Nested
	@SpringBootTest
	@Import(TestSessionConfig.class)
	class WithoutMailHost {

		@Autowired
		AuthMailSender mailSender;

		@Test
		void usesLogSender() {
			assertThat(mailSender).isInstanceOf(LogAuthMailSender.class);
		}
	}

	@Nested
	@SpringBootTest(properties = { "spring.mail.host=localhost", "spring.mail.username=sender@example.com" })
	@Import(TestSessionConfig.class)
	class WithMailHost {

		@Autowired
		AuthMailSender mailSender;

		@Test
		void usesSmtpSender() {
			assertThat(mailSender).isInstanceOf(SmtpAuthMailSender.class);
		}
	}
}
