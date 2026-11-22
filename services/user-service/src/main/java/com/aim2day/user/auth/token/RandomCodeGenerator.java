package com.aim2day.user.auth.token;

import org.springframework.stereotype.Component;

/**
 * 운영용 인증번호 생성기. CodeGenerator 인터페이스의 기본 구현이며, EmailCodeService가 주입받아 가입 인증번호를 만든다.
 * 실제 난수 생성은 TokenCodec(SecureRandom)에 맡긴다. 테스트에서는 이 빈 대신 값을 고정한 구현을 넣어
 * 같은 번호가 다시 나오는 경쟁 상황을 재현한다. 패키지 밖에서 직접 쓸 일이 없으므로 접근 범위를 패키지로 제한했다.
 */
@Component
class RandomCodeGenerator implements CodeGenerator {

	/** 앞자리가 0일 수 있는 6자리 숫자 문자열(000000~999999)을 새로 만든다. 호출할 때마다 암호학적 난수를 쓴다. */
	@Override
	public String newCode() {
		return TokenCodec.newSixDigitCode();
	}
}
