package com.aim2day.user.auth.token;

import org.springframework.stereotype.Component;

@Component
class RandomCodeGenerator implements CodeGenerator {

	@Override
	public String newCode() {
		return TokenCodec.newSixDigitCode();
	}
}
