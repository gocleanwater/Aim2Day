package com.aim2day.user.auth.token;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/** 토큰 생성과 해시. Redis 키에는 원문이 아니라 해시만 쓴다. */
public final class TokenCodec {

	private static final SecureRandom RANDOM = new SecureRandom();

	private TokenCodec() {
	}

	/** 32바이트 난수를 패딩 없는 Base64URL로 인코딩한다(API 명세 §3.1). 추측이 불가능해야 하므로 SecureRandom을 쓴다. */
	public static String newToken() {
		byte[] bytes = new byte[32];
		RANDOM.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	/**
	 * SHA-256 16진수 해시. Redis가 유출·덤프되어도 키만으로는 유효한 링크를 만들 수 없게 한다.
	 * 토큰은 256비트 난수라 salt 없는 빠른 해시로 충분하다.
	 */
	public static String sha256Hex(String value) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		}
		catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", e);
		}
	}

	/**
	 * 6자리 인증번호(000000~999999). nextInt(bound)는 편향 없이 균등하게 고르고, 앞의 0을 유지하려고 문자열로 만든다.
	 */
	public static String newSixDigitCode() {
		return String.format(java.util.Locale.ROOT, "%06d", RANDOM.nextInt(1_000_000));
	}

	/**
	 * 인증번호 해시. 번호는 6자리뿐이라 해시만으로는 무차별 대입을 막지 못하므로(Redis 유출 시 100만 번 계산이면 복원),
	 * 보안은 TTL(10분)·시도 5회 제한이 맡고 해시는 "원문을 그대로 저장하지 않는다"는 최소 장치다.
	 * 이메일을 섞어 다른 이메일의 같은 번호가 같은 해시가 되지 않게 한다.
	 */
	public static String codeHash(String email, String code) {
		return sha256Hex(email + ":" + code);
	}
}
