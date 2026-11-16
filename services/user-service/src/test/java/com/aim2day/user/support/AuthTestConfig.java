package com.aim2day.user.support;

import com.aim2day.user.auth.token.AuthTokenStore;
import com.aim2day.user.auth.token.TokenCodec;
import com.aim2day.user.mail.AuthMailSender;
import com.aim2day.user.mail.MailDeliveryException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Redis·SMTP 없이 가입 흐름을 검증하기 위한 메모리 저장소와 기록용 메일 발송기. */
@TestConfiguration
public class AuthTestConfig {

	@Bean
	@Primary
	InMemoryTokenStore inMemoryTokenStore() {
		return new InMemoryTokenStore();
	}

	@Bean
	@Primary
	RecordingMailSender recordingMailSender() {
		return new RecordingMailSender();
	}

	/**
	 * RedisAuthTokenStore와 같은 의미의 메모리 구현. Redis 구현이 Lua 스크립트(저장·확인·소유자 정리)로 원자 실행하는
	 * 것과 같은 경계로 메서드를 두고 각각을 synchronized로 둔다. 그래서 병렬 테스트가 운영과 같은 종류의 경쟁
	 * (시도 5회 초과·번호 이중 사용·남의 번호 삭제)을 검증한다.
	 */
	public static class InMemoryTokenStore implements AuthTokenStore {

		private record CodeEntry(String hash, int attempts, String owner) {
		}

		private final Map<String, CodeEntry> codes = new HashMap<>(); // sha256(email) -> 해시·남은 시도·소유자
		private final Map<String, String> tickets = new HashMap<>(); // sha256(token) -> email
		private final Map<String, String> throttled = new HashMap<>(); // purpose:email -> 소유자

		@Override
		public synchronized boolean saveCode(String email, String code, String ownerId) {
			// Redis SAVE_CODE_LUA와 같이 제한 키의 소유자가 나일 때만 저장한다.
			if (!ownerId.equals(throttled.get(PURPOSE_SIGNUP + ":" + email))) {
				return false;
			}
			// 덮어쓰기 한 번: 이전 번호와 시도 횟수가 함께 사라진다.
			codes.put(TokenCodec.sha256Hex(email),
					new CodeEntry(TokenCodec.codeHash(email, code), MAX_ATTEMPTS, ownerId));
			return true;
		}

		@Override
		public synchronized long mailThrottleRemainingSeconds(String purpose, String email) {
			return THROTTLE_SECONDS;
		}

		/** Redis의 VERIFY_CODE_LUA와 같은 판정. 정답은 남은 횟수와 무관, 차감은 오답일 때만. */
		@Override
		public synchronized CodeCheck verifyCode(String email, String code) {
			String token = TokenCodec.newToken();
			String key = TokenCodec.sha256Hex(email);
			CodeEntry entry = codes.get(key);
			if (entry == null) {
				return CodeCheck.expired();
			}
			if (entry.hash().equals(TokenCodec.codeHash(email, code))) {
				codes.remove(key);
				tickets.put(TokenCodec.sha256Hex(token), email);
				return CodeCheck.ok(token);
			}
			int remaining = entry.attempts() - 1;
			if (remaining <= 0) {
				codes.remove(key);
				return CodeCheck.expired();
			}
			codes.put(key, new CodeEntry(entry.hash(), remaining, entry.owner()));
			return CodeCheck.wrong(remaining);
		}

		@Override
		public synchronized Optional<String> consumeTicket(String token) {
			return Optional.ofNullable(tickets.remove(TokenCodec.sha256Hex(token)));
		}

		@Override
		public synchronized long acquireMailThrottle(String purpose, String email, String ownerId) {
			return throttled.putIfAbsent(purpose + ":" + email, ownerId) == null ? 0 : THROTTLE_SECONDS;
		}

		@Override
		public synchronized void releaseIfOwner(String purpose, String email, String ownerId) {
			throttled.remove(purpose + ":" + email, ownerId);
			String key = TokenCodec.sha256Hex(email);
			CodeEntry entry = codes.get(key);
			if (entry != null && ownerId.equals(entry.owner())) {
				codes.remove(key);
			}
		}

		private final Map<String, Long> resetTokens = new HashMap<>(); // sha256(token) -> userId
		private final Map<Long, String> latestReset = new HashMap<>(); // userId -> 최신 토큰 해시(보조 키)

		/** 테스트용 훅: 발급 직전(제한 소유 확인 전)에 실행한다. 요청이 "제한 획득 후 발급 전"에 멈추는 상황을 만든다. */
		public volatile Runnable beforeIssueHook;
		/** 테스트용: true면 발급이 저장소 예외로 실패한다. */
		public volatile boolean failIssue;

		/**
		 * Redis ISSUE_RESET_TOKEN_LUA와 같다: 제한 소유 확인 + 이전 토큰 삭제 + 새 토큰 + 보조 키를 한 번에
		 * (synchronized = 스크립트의 원자성). 제한 키의 값이 ownerId가 아니면 아무것도 쓰지 않고 false다.
		 */
		@Override
		public boolean issueResetToken(long userId, String token, String email, String ownerId) {
			Runnable hook = beforeIssueHook;
			if (hook != null) {
				hook.run(); // synchronized 밖에서 실행해야 다른 스레드가 저장소를 쓸 수 있다.
			}
			if (failIssue) {
				throw new IllegalStateException("redis down");
			}
			return issueLocked(userId, token, email, ownerId);
		}

		private synchronized boolean issueLocked(long userId, String token, String email, String ownerId) {
			if (!ownerId.equals(throttled.get(PURPOSE_RESET + ":" + email))) {
				return false;
			}
			String old = latestReset.get(userId);
			if (old != null) {
				resetTokens.remove(old);
			}
			String hash = TokenCodec.sha256Hex(token);
			resetTokens.put(hash, userId);
			latestReset.put(userId, hash);
			return true;
		}

		/** Redis CONSUME_RESET_TOKEN_LUA와 같다: 읽고 지우며, 보조 키는 이 해시를 가리킬 때만 지운다. */
		@Override
		public synchronized Optional<Long> consumeResetToken(String token) {
			String hash = TokenCodec.sha256Hex(token);
			Long userId = resetTokens.remove(hash);
			if (userId != null) {
				latestReset.remove(userId, hash);
			}
			return Optional.ofNullable(userId);
		}

		/** Redis RELEASE_RESET_IF_LATEST_LUA와 같다. */
		@Override
		public synchronized void releaseResetTokenIfLatest(long userId, String token) {
			String hash = TokenCodec.sha256Hex(token);
			if (hash.equals(latestReset.get(userId))) {
				latestReset.remove(userId);
				resetTokens.remove(hash);
			}
		}

		/** 테스트용: 저장된 재설정 토큰 수(해시 기준). */
		public synchronized int resetTokenCount() {
			return resetTokens.size();
		}

		/** 테스트용: 이 토큰 원문으로 소비할 수 있는 토큰이 저장돼 있는지(소비하지 않는다). */
		public synchronized boolean hasResetToken(String token) {
			return resetTokens.containsKey(TokenCodec.sha256Hex(token));
		}

		/** 테스트용: 사용자별 최신 토큰 보조 키가 있는지. */
		public synchronized boolean hasLatestResetMarker(long userId) {
			return latestReset.containsKey(userId);
		}

		/** 테스트용: 재설정 토큰이 원문이 아니라 해시로 보관되는지 확인하는 데 쓴다. */
		public synchronized boolean containsRawResetToken(String token) {
			return resetTokens.containsKey(token);
		}

		/** 테스트용: 가입권을 바로 발급한다(번호 확인 단계를 건너뛴 상황을 만들 때). */
		public synchronized String issueTicket(String email) {
			String token = TokenCodec.newToken();
			tickets.put(TokenCodec.sha256Hex(token), email);
			return token;
		}

		/** 테스트용: 60초 경과를 흉내 내 재요청 제한만 없앤다(번호는 그대로). */
		public synchronized void expireThrottle(String purpose, String email) {
			throttled.remove(purpose + ":" + email);
		}

		/** 테스트용: TTL 경과를 흉내 내 번호만 없앤다. */
		public synchronized void expireCode(String email) {
			codes.remove(TokenCodec.sha256Hex(email));
		}

		/** 테스트용: 번호가 저장돼 있는지. */
		public synchronized boolean hasCode(String email) {
			return codes.containsKey(TokenCodec.sha256Hex(email));
		}

		/** 테스트용: 번호 저장소가 원문이 아니라 해시를 보관하는지 확인하는 데 쓴다. */
		public synchronized boolean storesRawCode(String email, String code) {
			CodeEntry entry = codes.get(TokenCodec.sha256Hex(email));
			return entry != null && entry.hash().equals(code);
		}

		public synchronized boolean isThrottled(String purpose, String email) {
			return throttled.containsKey(purpose + ":" + email);
		}

		public synchronized void clear() {
			codes.clear();
			tickets.clear();
			throttled.clear();
			resetTokens.clear();
			latestReset.clear();
			beforeIssueHook = null;
			failIssue = false;
		}

		public synchronized int ticketCount() {
			return tickets.size();
		}

		/** 가입권이 원문이 아니라 해시로 보관되는지 확인하는 용도. */
		public synchronized boolean containsRawToken(String token) {
			return tickets.containsKey(token);
		}
	}

	/**
	 * 가입 인증번호 발송만 람다로 지정하는 간이 발송기. AuthMailSender가 메서드 둘을 갖게 되어 람다를 직접 쓸 수 없다.
	 * 재설정 메일은 이런 테스트에서 호출되지 않아야 하므로 부르면 실패한다.
	 */
	public static AuthMailSender signupSender(java.util.function.BiConsumer<String, String> onSignupCode) {
		return new AuthMailSender() {
			@Override
			public void sendSignupCode(String to, String code) {
				onSignupCode.accept(to, code);
			}

			@Override
			public void sendPasswordResetLink(String to, String link) {
				throw new UnsupportedOperationException("이 테스트에서는 재설정 메일을 보내지 않는다");
			}
		};
	}

	public static class RecordingMailSender implements AuthMailSender {

		public record Sent(String to, String code) {
		}

		private final List<Sent> sent = new CopyOnWriteArrayList<>();
		private volatile boolean fail;

		@Override
		public void sendSignupCode(String to, String code) {
			if (fail) {
				throw new MailDeliveryException("test failure", new RuntimeException("boom"));
			}
			sent.add(new Sent(to, code));
		}

		public record SentReset(String to, String link) {
		}

		private final List<SentReset> sentReset = new CopyOnWriteArrayList<>();

		@Override
		public void sendPasswordResetLink(String to, String link) {
			if (fail) {
				throw new MailDeliveryException("test failure", new RuntimeException("boom"));
			}
			sentReset.add(new SentReset(to, link));
		}

		public List<SentReset> sentReset() {
			return new ArrayList<>(sentReset);
		}

		public SentReset lastReset() {
			return sentReset.get(sentReset.size() - 1);
		}

		public List<Sent> sent() {
			return new ArrayList<>(sent);
		}

		public Sent last() {
			return sent.get(sent.size() - 1);
		}

		public void failNext(boolean fail) {
			this.fail = fail;
		}

		public void clear() {
			sent.clear();
			sentReset.clear();
			fail = false;
		}
	}
}
