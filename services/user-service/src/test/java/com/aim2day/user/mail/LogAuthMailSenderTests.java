package com.aim2day.user.mail;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class LogAuthMailSenderTests {

	@Test
	void logsMaskedAddressAndCodeButNeverTheRawEmail(CapturedOutput output) {
		new LogAuthMailSender().sendSignupCode("user@gmail.com", "048213");

		assertThat(output.getAll()).contains("[MAIL] to=u***@gmail.com purpose=signup-code code=048213");
		assertThat(output.getAll()).doesNotContain("user@gmail.com");
	}

	@Test
	void masksEdgeCases() {
		assertThat(MailAddressMasker.mask("a@b.com")).isEqualTo("a***@b.com");
		assertThat(MailAddressMasker.mask("not-an-email")).isEqualTo("***");
		assertThat(MailAddressMasker.mask("@b.com")).isEqualTo("***");
		assertThat(MailAddressMasker.mask(null)).isEqualTo("***");
	}

	@Test
	void maskedResultAlwaysDiffersFromInput(CapturedOutput output) {
		for (String email : new String[] { "u***@gmail.com", "***@gmail.com", "***@***", "*@x", "a@b" }) {
			assertThat(MailAddressMasker.mask(email)).isNotEqualTo(email);
		}
		assertThat(MailAddressMasker.mask("u***@gmail.com")).isEqualTo("***@gmail.com");
		// 로그에도 원문이 남지 않는다.
		new LogAuthMailSender().sendSignupCode("u***@gmail.com", "048213");
		assertThat(output.getAll()).doesNotContain("to=u***@gmail.com");
	}
}
