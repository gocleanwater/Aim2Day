package com.aim2day.user.auth.token;

/** 인증번호 생성기. 운영은 난수, 테스트는 값을 고정해 "같은 번호가 다시 나온" 경쟁 상황을 재현할 수 있게 분리했다. */
@FunctionalInterface
public interface CodeGenerator {

	String newCode();
}
