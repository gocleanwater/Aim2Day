package com.aim2day.user.mail;

/** 로그에 남길 이메일 마스킹. 이메일 원문은 어떤 로그에도 남기지 않는다(공유 계약 §2). */
public final class MailAddressMasker {

	private static final String MASK = "***";

	private MailAddressMasker() {
	}

	/**
	 * user@gmail.com -> u***@gmail.com. 로컬 파트는 첫 글자만 남기고, 형식이 이상하면 전체를 가린다.
	 * 결과는 항상 원문과 달라야 한다. "u***@gmail.com"처럼 이미 마스킹 모양인 주소는 첫 글자 마스킹 결과가
	 * 원문과 같아지므로, 그 경우 로컬 파트 전체를 가리고, 그래도 같으면(예: "***@***") 전체를 "***"로 가린다.
	 */
	public static String mask(String email) {
		if (email == null) {
			return MASK;
		}
		int at = email.lastIndexOf('@');
		if (at < 1) {
			return MASK;
		}
		// 첫 글자가 서로게이트 쌍일 수 있으므로 코드 포인트 단위로 자른다.
		int firstEnd = email.offsetByCodePoints(0, 1);
		String masked = email.substring(0, firstEnd) + MASK + email.substring(at);
		if (!masked.equals(email)) {
			return masked;
		}
		masked = MASK + email.substring(at);
		if (!masked.equals(email)) {
			return masked;
		}
		return MASK; // "@"가 없는 고정 문자열이라 이메일 원문과 같을 수 없다
	}
}
