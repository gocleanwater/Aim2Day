package com.aim2day.goal.settings;

import com.aim2day.goal.common.exception.BusinessException;
import com.aim2day.goal.common.exception.CommonErrorCode;
import com.aim2day.goal.common.exception.FieldErrorDetail;
import com.aim2day.goal.common.exception.ValidationReason;
import com.aim2day.goal.common.exception.VersionGuard;
import com.aim2day.goal.common.time.CalendarMath;
import com.aim2day.goal.common.tx.Tx;
import com.aim2day.goal.settings.dto.SettingsDtos.UpdateSettingsRequest;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 환경설정 조회·수정. 인스턴스 메모리에 아무것도 두지 않고 모든 상태를 DB에서 읽는다.
 * 메서드 전체에 @Transactional을 걸지 않고 TransactionTemplate으로 트랜잭션 경계를 직접 정한다.
 * 첫 생성 경합에서 실패한 트랜잭션과 재조회가 같은 영속성 컨텍스트를 쓰면 안 되기 때문이다.
 */
@Service
public class SettingsService {

	static final DayOfWeek DEFAULT_WEEK_START = DayOfWeek.SUNDAY;
	static final String DEFAULT_TIMEZONE = "Asia/Seoul";

	private final UserSettingRepository repository;
	private final Clock clock;
	private final TransactionTemplate tx;
	private final TransactionTemplate newTx;

	public SettingsService(UserSettingRepository repository, Clock clock, PlatformTransactionManager txManager) {
		this.repository = repository;
		this.clock = clock;
		this.tx = Tx.write(txManager);
		// 생성 시도는 항상 독립 트랜잭션에서 한다. 유니크 위반으로 롤백돼도 호출자 쪽에 영향이 없다.
		this.newTx = Tx.requiresNew(txManager);
	}

	/**
	 * 설정을 조회하고, 없으면 기본값으로 만든다.
	 * 두 인스턴스가 같은 사용자의 첫 조회를 동시에 받으면 둘 다 "없음"을 보고 INSERT를 시도한다.
	 * user_id 유니크 제약이 한쪽을 DataIntegrityViolationException으로 막으므로, 그 경우
	 * 이미 만들어진 행을 새로 읽어 두 요청 모두 성공시킨다.
	 * 모든 조회·생성이 REQUIRES_NEW라 반환 엔티티는 호출자 영속성 컨텍스트와 분리(detached)된다.
	 * 이후 서비스는 이 값을 읽기 전용으로만 쓴다(수정은 자기 트랜잭션에서 다시 읽어 한다).
	 */
	public UserSetting getOrCreate(long userId) {
		UserSetting found = findInNewTx(userId);
		if (found != null) {
			return found;
		}
		try {
			return newTx.execute(status ->
					repository.saveAndFlush(new UserSetting(userId, DEFAULT_WEEK_START, DEFAULT_TIMEZONE)));
		} catch (DataIntegrityViolationException e) {
			// 호출자가 이미 트랜잭션 안이면 MySQL REPEATABLE READ 스냅샷이 "행 없음"을 유지하므로,
			// 다른 요청이 커밋한 행을 보려면 재조회도 새 트랜잭션에서 해야 한다.
			UserSetting existing = findInNewTx(userId);
			if (existing == null) {
				throw e;
			}
			return existing;
		}
	}

	/** 최초 조회도 호출자 스냅샷과 분리한다. 반환 엔티티는 호출자 영속성 컨텍스트에 속하지 않으므로 읽기 전용으로만 쓴다. */
	private UserSetting findInNewTx(long userId) {
		return newTx.execute(status -> repository.findByUserId(userId).orElse(null));
	}

	/** 단위 2 이후 모든 서비스가 "오늘·이번 주·이번 달"을 얻는 유일한 경로다. */
	public UserTime userTime(long userId) {
		return toUserTime(getOrCreate(userId));
	}

	public UserTime toUserTime(UserSetting setting) {
		ZoneId zone = ZoneId.of(setting.getTimezone());
		LocalDate today = CalendarMath.today(clock, zone);
		return new UserTime(zone, setting.getWeekStartDay(), today,
				CalendarMath.weekContaining(today, setting.getWeekStartDay()), YearMonth.from(today));
	}

	public UserSetting update(long userId, UpdateSettingsRequest req) {
		// ZoneId.of는 오프셋(+09:00)·약어(KST)도 받으므로 쓰지 않고, 등록된 IANA 이름과 정확히 비교한다.
		if (!ZoneId.getAvailableZoneIds().contains(req.timezone())) {
			throw new BusinessException(CommonErrorCode.VALIDATION_FAILED, Map.of("fieldErrors",
					List.of(new FieldErrorDetail("timezone", ValidationReason.INVALID_FORMAT.name()))));
		}
		getOrCreate(userId);
		// 사전 비교와 커밋 시점 충돌 모두 currentVersion을 채워야 한다. 트랜잭션 바깥에서 충돌을 번역해
		// 롤백이 끝난 뒤 새 트랜잭션으로 최신 version을 읽는다.
		return VersionGuard.translateConflict(() -> tx.execute(status -> {
			UserSetting setting = repository.findByUserId(userId).orElseThrow();
			// 먼저 version을 비교해 409의 currentVersion을 채운다. 비교 뒤 다른 인스턴스가 먼저 커밋하면
			// saveAndFlush의 @Version UPDATE가 충돌하고 translateConflict가 이를 409로 바꾼다.
			VersionGuard.check(req.version(), setting.getVersion());
			setting.change(req.weekStartDay(), req.timezone());
			// flush해야 증가한 version이 응답에 담긴다.
			return repository.saveAndFlush(setting);
		}), () -> findInNewTx(userId).getVersion());
	}
}
