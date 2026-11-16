package com.aim2day.user.auth.token;

import static org.assertj.core.api.Assertions.assertThat;

import com.aim2day.user.auth.token.AuthTokenStore.CodeCheck;
import com.aim2day.user.auth.token.AuthTokenStore.CodeStatus;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * 실제 Redis 없이 RedisAuthTokenStore를 검증한다. 가짜 템플릿이 스크립트 실행 호출(스크립트·키·인자)을 기록하고
 * 미리 정한 결과를 돌려준다. 그래서 "어떤 순서로 어떤 키·인자로 스크립트를 부르는가"와 결과 해석은 검증되지만,
 * Lua 문법과 Redis 안에서의 실제 원자 실행은 검증하지 못한다(운영 환경 통합 확인이 필요).
 */
class RedisAuthTokenStoreTests {

	private static final String EMAIL = "user@example.com";

	record Call(String script, List<String> keys, List<Object> args) {
	}

	/** execute(script, keys, args)만 가로채는 가짜 템플릿. */
	static class FakeRedis extends StringRedisTemplate {

		final List<Call> calls = new ArrayList<>();
		final List<Function<Call, Object>> answers = new ArrayList<>();

		FakeRedis answer(Object... results) {
			for (Object r : results) {
				answers.add(call -> r);
			}
			return this;
		}

		@Override
		@SuppressWarnings("unchecked")
		public <T> T execute(RedisScript<T> script, List<String> keys, Object... args) {
			Call call = new Call(script.getScriptAsString(), keys, Arrays.asList(args));
			calls.add(call);
			return (T) answers.get(calls.size() - 1).apply(call);
		}
	}

	private static String keyOf(String email) {
		return "auth:signup-code:" + TokenCodec.sha256Hex(email);
	}

	@Test
	void keysFollowSpecification() {
		assertThat(RedisAuthTokenStore.codeKey(EMAIL)).isEqualTo("auth:signup-code:" + TokenCodec.sha256Hex(EMAIL))
				.doesNotContain(EMAIL);
		assertThat(RedisAuthTokenStore.ticketKey("tok")).isEqualTo("auth:signup-ticket:" + TokenCodec.sha256Hex("tok"));
		assertThat(RedisAuthTokenStore.throttleKey("signup", EMAIL))
				.isEqualTo("auth:mail-throttle:signup:" + TokenCodec.sha256Hex(EMAIL));
		assertThat(TokenCodec.sha256Hex(EMAIL)).hasSize(64);
	}


	@Test
	void saveCodeOverwritesInOneScriptWithHashAttemptsTtlAndOwner() {
		FakeRedis redis = new FakeRedis().answer(1L);
		boolean saved = new RedisAuthTokenStore(redis).saveCode(EMAIL, "048213", "owner-1");
		assertThat(saved).isTrue();

		assertThat(redis.calls).hasSize(1);
		Call call = redis.calls.get(0);
		assertThat(call.script()).isEqualTo(RedisAuthTokenStore.SAVE_CODE_LUA);
		assertThat(call.keys()).containsExactly(keyOf(EMAIL), RedisAuthTokenStore.throttleKey("signup", EMAIL));
		// 인자: 해시(원문 아님), 시도 횟수 5, TTL 600초, 소유자 ID
		assertThat(call.args()).containsExactly(TokenCodec.codeHash(EMAIL, "048213"), "5", "600", "owner-1");
		assertThat(call.args()).doesNotContain("048213");
	}

	@Test
	void saveCodeReturnsFalseWhenScriptRefusesBecauseThrottleIsNotMine() {
		assertThat(new RedisAuthTokenStore(new FakeRedis().answer(0L)).saveCode(EMAIL, "048213", "owner-1")).isFalse();
		assertThat(new RedisAuthTokenStore(new FakeRedis().answer((Object) null)).saveCode(EMAIL, "048213", "o")).isFalse();
	}

	@Test
	void verifyIsOneScriptWithCodeKeyTicketKeyAndHashedArguments() {
		FakeRedis redis = new FakeRedis().answer(0L);

		CodeCheck check = new RedisAuthTokenStore(redis).verifyCode(EMAIL, "048213");

		assertThat(check.status()).isEqualTo(CodeStatus.OK);
		assertThat(redis.calls).hasSize(1); // 별도의 삭제·가입권 저장 호출이 없다.
		Call call = redis.calls.get(0);
		assertThat(call.script()).isEqualTo(RedisAuthTokenStore.VERIFY_CODE_LUA);
		// 가입권 키는 응답으로 돌려주는 원문 토큰의 해시로 만든 키다.
		assertThat(call.keys()).hasSize(2);
		assertThat(call.keys().get(0)).isEqualTo(keyOf(EMAIL));
		assertThat(check.signupToken()).matches("[A-Za-z0-9_-]{43}");
		assertThat(call.keys().get(1)).isEqualTo(RedisAuthTokenStore.ticketKey(check.signupToken()));
		assertThat(call.args()).containsExactly(TokenCodec.codeHash(EMAIL, "048213"), EMAIL, "1800");
	}

	@Test
	void verifyMapsScriptResults() {
		CodeCheck wrong = new RedisAuthTokenStore(new FakeRedis().answer(3L)).verifyCode(EMAIL, "111111");
		assertThat(wrong.status()).isEqualTo(CodeStatus.WRONG);
		assertThat(wrong.remainingAttempts()).isEqualTo(3);
		assertThat(wrong.signupToken()).isNull();

		assertThat(new RedisAuthTokenStore(new FakeRedis().answer(-1L)).verifyCode(EMAIL, "048213").status())
				.isEqualTo(CodeStatus.EXPIRED);
		assertThat(new RedisAuthTokenStore(new FakeRedis().answer((Object) null)).verifyCode(EMAIL, "048213").status())
				.isEqualTo(CodeStatus.EXPIRED);
	}

	@Test
	void releaseIfOwnerIsOneScriptOverThrottleAndCodeKeys() {
		FakeRedis redis = new FakeRedis().answer(2L);
		new RedisAuthTokenStore(redis).releaseIfOwner("signup", EMAIL, "owner-1");

		assertThat(redis.calls).hasSize(1);
		Call call = redis.calls.get(0);
		assertThat(call.script()).isEqualTo(RedisAuthTokenStore.RELEASE_IF_OWNER_LUA);
		assertThat(call.keys()).containsExactly(RedisAuthTokenStore.throttleKey("signup", EMAIL), keyOf(EMAIL));
		assertThat(call.args()).containsExactly("owner-1");
	}

	/**
	 * Lua를 실행할 수 없는 환경에서 할 수 있는 최소한의 정적 점검: 참조하는 KEYS/ARGV 번호가 실제로 넘기는 수를 넘지 않고
	 * if/end 짝이 맞는다. 실제 동작은 Redis에서 별도로 검증한다.
	 */
	@Test
	void luaScriptsPassStaticChecks() {
		record Spec(String name, String lua, int keyc, int argc) {
		}
		for (Spec spec : List.of(
				new Spec("save", RedisAuthTokenStore.SAVE_CODE_LUA, 2, 4),
				new Spec("verify", RedisAuthTokenStore.VERIFY_CODE_LUA, 2, 3),
				new Spec("release", RedisAuthTokenStore.RELEASE_IF_OWNER_LUA, 2, 1))) {
			Matcher keys = Pattern.compile("KEYS\\[(\\d+)\\]").matcher(spec.lua());
			while (keys.find()) {
				assertThat(Integer.parseInt(keys.group(1))).as(spec.name()).isBetween(1, spec.keyc());
			}
			Matcher argv = Pattern.compile("ARGV\\[(\\d+)\\]").matcher(spec.lua());
			while (argv.find()) {
				assertThat(Integer.parseInt(argv.group(1))).as(spec.name()).isBetween(1, spec.argc());
			}
			long thens = Pattern.compile("\\bthen\\b").matcher(spec.lua()).results().count();
			long ends = Pattern.compile("\\bend\\b").matcher(spec.lua()).results().count();
			assertThat(ends).as(spec.name() + " then/end 짝").isEqualTo(thens);
			assertThat(spec.lua().trim()).as(spec.name()).contains("return");
		}
	}
}
