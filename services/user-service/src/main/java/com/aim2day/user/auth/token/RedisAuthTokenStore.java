package com.aim2day.user.auth.token;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * Redis 구현. 키 규칙은 API 명세 §3.1·ERD Redis 절을 따른다.
 * <ul>
 * <li>auth:signup-code:{SHA-256(email)} -> 해시(필드 h)·남은 시도 횟수(n)·발급 소유자 ID(o), TTL 10분</li>
 * <li>auth:signup-ticket:{SHA-256(token)} -> email, TTL 30분, GETDEL로 소비</li>
 * <li>auth:mail-throttle:{purpose}:{SHA-256(email)} -> 발급 소유자 ID, TTL 60초, SET NX EX</li>
 * <li>auth:reset:{SHA-256(token)} -> userId, TTL 30분</li>
 * <li>auth:reset-user:{userId} -> 그 사용자의 최신 재설정 토큰 해시(보조 키), TTL 30분</li>
 * </ul>
 * 여러 키를 건드리는 동작(확인·저장·실패 정리)은 Lua 스크립트 하나로 처리한다. Redis는 스크립트를 한 덩어리로
 * 실행하므로 중간에 다른 명령이 끼지 않고, 그래서 "명령 사이에서 실패하면 둘 다 잃는" 상태가 생기지 않는다.
 * 키를 둘 이상 쓰는 스크립트는 단일 Redis 인스턴스를 전제한다(클러스터라면 같은 해시 슬롯이어야 한다).
 */
@Component
public class RedisAuthTokenStore implements AuthTokenStore {

	/**
	 * 번호 저장(덮어쓰기). KEYS[1]=코드 키, KEYS[2]=재요청 제한 키,
	 * ARGV[1]=번호 해시, ARGV[2]=시도 횟수, ARGV[3]=TTL 초, ARGV[4]=소유자 ID.
	 * 반환: 1=저장함, 0=저장 안 함(제한 키의 값이 내 소유자 ID가 아님).
	 * <p>
	 * 제한 키의 소유자를 확인하는 이유: 제한을 얻은 뒤 저장 전에 60초 넘게 멈춘 요청 A가 있으면, 그 사이 제한이 만료되어
	 * 요청 B가 새 제한을 얻고 번호를 저장·발송할 수 있다. 확인 없이 A가 재개되어 저장하면 B의 번호를 덮어써 B가 받은
	 * 메일의 번호가 쓸 수 없게 된다. "제한 확인 + 저장"을 한 스크립트로 묶어 그 사이에 끼어들 틈이 없게 한다.
	 * 확인에 통과하면 DEL 후 HSET으로 이전 번호·시도 횟수·소유자를 함께 없애고 TTL도 새로 건다. 중간 상태가 보이지 않는다.
	 */
	static final String SAVE_CODE_LUA = """
			if redis.call('GET', KEYS[2]) ~= ARGV[4] then
			  return 0
			end
			redis.call('DEL', KEYS[1])
			redis.call('HSET', KEYS[1], 'h', ARGV[1], 'n', ARGV[2], 'o', ARGV[4])
			redis.call('EXPIRE', KEYS[1], ARGV[3])
			return 1""";

	/**
	 * 인증번호 확인. KEYS[1]=코드 키, KEYS[2]=가입권 키(해시 포함 완성된 키),
	 * ARGV[1]=제출 번호 해시, ARGV[2]=email, ARGV[3]=가입권 TTL 초.
	 * 반환: -1=EXPIRED(없음·만료·5회 실패), 0=OK, 1 이상=WRONG(남은 횟수).
	 * <p>
	 * 비교·차감·삭제·가입권 저장을 한 스크립트에 묶는 이유: (1) 정답은 키가 있는 한 남은 횟수와 무관하게 성공해야 하므로
	 * 차감은 오답일 때만 한다. 비교 전에 먼저 차감하면 4번 틀린 뒤 정답과 다른 요청이 겹칠 때 정답이 만료 처리된다.
	 * (2) 번호 삭제와 가입권 저장이 따로 실행되면 그 사이 실패로 둘 다 잃을 수 있다.
	 * 해시 비교를 Lua의 ==로 해도 되는 이유: 비교 대상은 SHA-256 해시이고 공격자는 해시의 앞부분을 조절할 수 없어서
	 * 앞부분이 일치하는 길이에 따른 시간 차로 알아낼 수 있는 정보가 없다.
	 */
	static final String VERIFY_CODE_LUA = """
			local h = redis.call('HGET', KEYS[1], 'h')
			if not h then return -1 end
			if h == ARGV[1] then
			  redis.call('DEL', KEYS[1])
			  redis.call('SET', KEYS[2], ARGV[2], 'EX', ARGV[3])
			  return 0
			end
			local n = redis.call('HINCRBY', KEYS[1], 'n', -1)
			if n <= 0 then
			  redis.call('DEL', KEYS[1])
			  return -1
			end
			return n""";

	/**
	 * 발급 실패 정리. KEYS[1]=재요청 제한 키, KEYS[2]=코드 키, ARGV[1]=소유자 ID. 반환: 지운 키 개수(0~2).
	 * 제한 키의 값이 내 ID일 때만, 코드 레코드의 o가 내 ID일 때만 지운다. 이전 요청이 늦게 실패했을 때
	 * 그 사이 새 요청이 만든 제한·번호를 지우지 않게 하는 것이 목적이다.
	 */
	static final String RELEASE_IF_OWNER_LUA = """
			local d = 0
			if redis.call('GET', KEYS[1]) == ARGV[1] then
			  d = d + redis.call('DEL', KEYS[1])
			end
			if redis.call('HGET', KEYS[2], 'o') == ARGV[1] then
			  d = d + redis.call('DEL', KEYS[2])
			end
			return d""";

	/**
	 * 재설정 토큰 발급(교체). KEYS[1]=새 토큰 키(auth:reset:{newHash}), KEYS[2]=사용자별 최신 토큰 보조 키
	 * (auth:reset-user:{userId}), KEYS[3]=재요청 제한 키(auth:mail-throttle:reset:{emailHash}),
	 * ARGV[1]=userId, ARGV[2]=newHash, ARGV[3]=TTL 초, ARGV[4]=토큰 키 접두사("auth:reset:"), ARGV[5]=소유자 ID.
	 * 반환: 1=발급함, 0=발급 안 함(제한 키의 값이 내 소유자 ID가 아님).
	 * <p>
	 * 제한 키의 소유자를 확인하는 이유: 제한을 얻은 뒤 발급 전에 60초 넘게 멈춘 요청 A가 있으면, 그 사이 제한이 만료되어
	 * 요청 B가 새 제한을 얻고 토큰을 발급해 메일을 보낼 수 있다. 확인 없이 A가 재개되어 발급하면 B의 토큰을 지우고(교체)
	 * B가 받은 링크가 무효가 된다. "제한 확인 + 교체"를 한 스크립트로 묶어 그 사이에 끼어들 틈이 없게 하고,
	 * 소유권을 잃은 요청은 아무것도 쓰지 않는다.
	 * <p>
	 * 한 스크립트로 묶는 또 다른 이유: "이전 토큰 삭제 -> 새 토큰 저장 -> 보조 키 갱신"을 명령 세 개로 나누면 동시 발급 두 건이
	 * 서로의 보조 키 읽기와 쓰기 사이에 끼어들어, 각자 상대의 새 토큰이 아니라 옛 토큰만 지우고 둘 다 살아남을 수 있다
	 * (이전 링크가 살아남는다). 스크립트는 Redis에서 한 덩어리로 실행되므로 마지막에 실행된 발급만 남는다.
	 * 이전 토큰 키 이름은 보조 키 값(이전 해시)에 접두사를 붙여 스크립트 안에서 만든다. 실행 전에는 어떤 키를 지울지
	 * 모르기 때문에 KEYS로 미리 선언할 수 없다. 그래서 이 스크립트는 단일 Redis 인스턴스를 전제한다
	 * (클러스터라면 선언하지 않은 키 접근이 다른 슬롯일 수 있다).
	 * 보조 키 TTL은 토큰과 같게 건다. 토큰이 만료되면 보조 키도 함께 사라져 가리키는 대상 없는 키가 남지 않는다.
	 */
	static final String ISSUE_RESET_TOKEN_LUA = """
			if redis.call('GET', KEYS[3]) ~= ARGV[5] then
			  return 0
			end
			local old = redis.call('GET', KEYS[2])
			if old then
			  redis.call('DEL', ARGV[4] .. old)
			end
			redis.call('SET', KEYS[1], ARGV[1], 'EX', ARGV[3])
			redis.call('SET', KEYS[2], ARGV[2], 'EX', ARGV[3])
			return 1""";

	/**
	 * 재설정 토큰 소비. KEYS[1]=토큰 키(auth:reset:{hash}), ARGV[1]=hash, ARGV[2]=보조 키 접두사("auth:reset-user:").
	 * 반환: 사용자 ID(문자열 그대로), 토큰이 없거나 만료됐으면 nil(Java에서 null).
	 * 숫자로 바꾸지 않는 이유: Lua의 tonumber는 double이라 2^53을 넘는 ID의 정밀도를 잃을 수 있다. 변환은 Java의 Long.parseLong이 한다.
	 * <p>
	 * GETDEL로 읽기와 삭제를 한 동작에 묶어 같은 링크로 동시에 두 번 재설정하는 경쟁을 막는다. 보조 키는 사용자 ID를
	 * 읽은 뒤에야 이름을 알 수 있으므로 스크립트 안에서 접두사 + userId로 만든다(단일 Redis 전제, 위 발급 스크립트와 같다).
	 * 보조 키가 "방금 소비한 이 해시"를 가리킬 때만 지운다. 이미 더 새 토큰이 발급되어 보조 키가 새 해시를 가리킨다면
	 * (이 토큰은 발급 때 지워졌어야 하지만 만료 직전 경합 등으로 남은 경우) 새 토큰의 보조 키를 지우면 안 되기 때문이다.
	 */
	static final String CONSUME_RESET_TOKEN_LUA = """
			local uid = redis.call('GETDEL', KEYS[1])
			if not uid then
			  return false
			end
			local aux = ARGV[2] .. uid
			if redis.call('GET', aux) == ARGV[1] then
			  redis.call('DEL', aux)
			end
			return uid""";

	/**
	 * 메일 전송 실패 정리. KEYS[1]=토큰 키, KEYS[2]=보조 키, ARGV[1]=hash. 반환: 지운 키 개수(0 또는 2).
	 * 보조 키가 내 해시를 가리킬 때(= 내 토큰이 아직 최신일 때)만 둘 다 지운다. 이전 요청이 늦게 실패했을 때
	 * 그 사이 발급된 더 새 토큰의 보조 키를 지우지 않으려는 것이다. 내 토큰 키는 새 발급이 이미 지웠을 것이므로 둘 일이 없다.
	 */
	static final String RELEASE_RESET_IF_LATEST_LUA = """
			if redis.call('GET', KEYS[2]) == ARGV[1] then
			  return redis.call('DEL', KEYS[1], KEYS[2])
			end
			return 0""";

	static final String RESET_TOKEN_PREFIX = "auth:reset:";
	static final String RESET_USER_PREFIX = "auth:reset-user:";

	private static final DefaultRedisScript<Long> ISSUE_RESET_TOKEN =
			new DefaultRedisScript<>(ISSUE_RESET_TOKEN_LUA, Long.class);
	private static final DefaultRedisScript<String> CONSUME_RESET_TOKEN =
			new DefaultRedisScript<>(CONSUME_RESET_TOKEN_LUA, String.class);
	private static final DefaultRedisScript<Long> RELEASE_RESET_IF_LATEST =
			new DefaultRedisScript<>(RELEASE_RESET_IF_LATEST_LUA, Long.class);

	private static final DefaultRedisScript<Long> SAVE_CODE = new DefaultRedisScript<>(SAVE_CODE_LUA, Long.class);
	private static final DefaultRedisScript<Long> VERIFY_CODE = new DefaultRedisScript<>(VERIFY_CODE_LUA, Long.class);
	private static final DefaultRedisScript<Long> RELEASE_IF_OWNER =
			new DefaultRedisScript<>(RELEASE_IF_OWNER_LUA, Long.class);

	private final StringRedisTemplate redis;

	public RedisAuthTokenStore(StringRedisTemplate redis) {
		this.redis = redis;
	}

	@Override
	public boolean saveCode(String email, String code, String ownerId) {
		Long saved = redis.execute(SAVE_CODE, List.of(codeKey(email), throttleKey(PURPOSE_SIGNUP, email)),
				TokenCodec.codeHash(email, code), Integer.toString(MAX_ATTEMPTS), Integer.toString(CODE_TTL_SECONDS),
				ownerId);
		return saved != null && saved == 1L;
	}

	@Override
	public long mailThrottleRemainingSeconds(String purpose, String email) {
		Long ttl = redis.getExpire(throttleKey(purpose, email));
		// 키가 없거나(-2) TTL이 없거나(-1) 읽지 못하면 최소 1초로 안내한다.
		return ttl == null || ttl < 1 ? 1 : ttl;
	}

	@Override
	public CodeCheck verifyCode(String email, String code) {
		// 가입권 난수는 Java에서 미리 만든다. 성공 시에만 스크립트가 저장하고, 실패하면 버려진다.
		String token = TokenCodec.newToken();
		Long result = redis.execute(VERIFY_CODE, List.of(codeKey(email), ticketKey(token)),
				TokenCodec.codeHash(email, code), email, Integer.toString(TICKET_TTL_SECONDS));
		if (result == null || result < 0) {
			return CodeCheck.expired();
		}
		return result == 0 ? CodeCheck.ok(token) : CodeCheck.wrong(result.intValue());
	}

	@Override
	public Optional<String> consumeTicket(String token) {
		// GETDEL: 조회와 삭제를 한 명령으로 처리해, 같은 가입권으로 동시에 두 번 가입하는 경쟁을 막는다.
		return Optional.ofNullable(redis.opsForValue().getAndDelete(ticketKey(token)));
	}

	@Override
	public long acquireMailThrottle(String purpose, String email, String ownerId) {
		String key = throttleKey(purpose, email);
		// SET NX EX: 키가 없을 때만 만들고 TTL을 건다. 동시 요청 중 하나만 통과한다. 값은 소유자 ID다.
		Boolean acquired = redis.opsForValue().setIfAbsent(key, ownerId, Duration.ofSeconds(THROTTLE_SECONDS));
		if (Boolean.TRUE.equals(acquired)) {
			return 0;
		}
		Long ttl = redis.getExpire(key);
		// 만료 직전 경합 등으로 TTL을 못 읽으면 최소 1초로 안내한다.
		return ttl == null || ttl < 1 ? 1 : ttl;
	}

	@Override
	public void releaseIfOwner(String purpose, String email, String ownerId) {
		redis.execute(RELEASE_IF_OWNER, List.of(throttleKey(purpose, email), codeKey(email)), ownerId);
	}

	@Override
	public boolean issueResetToken(long userId, String token, String email, String ownerId) {
		String hash = TokenCodec.sha256Hex(token);
		Long issued = redis.execute(ISSUE_RESET_TOKEN,
				List.of(resetTokenKey(token), resetUserKey(userId), throttleKey(PURPOSE_RESET, email)),
				Long.toString(userId), hash, Integer.toString(RESET_TOKEN_TTL_SECONDS), RESET_TOKEN_PREFIX, ownerId);
		return issued != null && issued == 1L;
	}

	@Override
	public Optional<Long> consumeResetToken(String token) {
		String userId = redis.execute(CONSUME_RESET_TOKEN, List.of(resetTokenKey(token)),
				TokenCodec.sha256Hex(token), RESET_USER_PREFIX);
		return Optional.ofNullable(userId).map(Long::parseLong);
	}

	@Override
	public void releaseResetTokenIfLatest(long userId, String token) {
		redis.execute(RELEASE_RESET_IF_LATEST, List.of(resetTokenKey(token), resetUserKey(userId)),
				TokenCodec.sha256Hex(token));
	}

	static String resetTokenKey(String token) {
		return RESET_TOKEN_PREFIX + TokenCodec.sha256Hex(token);
	}

	static String resetUserKey(long userId) {
		return RESET_USER_PREFIX + userId;
	}

	static String codeKey(String email) {
		return "auth:signup-code:" + TokenCodec.sha256Hex(email);
	}

	static String ticketKey(String token) {
		return "auth:signup-ticket:" + TokenCodec.sha256Hex(token);
	}

	static String throttleKey(String purpose, String email) {
		return "auth:mail-throttle:" + purpose + ":" + TokenCodec.sha256Hex(email);
	}
}
