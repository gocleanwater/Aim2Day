package com.aim2day.goal.common.exception;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 수정 요청의 version과 DB의 현재 version을 비교한다(API 명세 §2.4).
 * 서비스가 JPA 갱신 전에 먼저 호출해 409 VERSION_CONFLICT의 details.currentVersion을 채운다.
 * Hibernate의 OptimisticLockException만으로는 최신 version을 알 수 없어서다.
 * 비교를 통과한 뒤 다른 인스턴스가 먼저 커밋한 경합은 @Version UPDATE가 막는다(GlobalExceptionHandler 안전망).
 */
public final class VersionGuard {

	private VersionGuard() {
	}

	/**
	 * 낙관적 동시성 사전 비교. 클라이언트가 본 version이 DB의 현재 version과 같은지 확인한다.
	 * requested가 null이면 400 VALIDATION_FAILED(version REQUIRED), 다르면 409 VERSION_CONFLICT와 currentVersion을 담아 던진다.
	 * 이 비교를 통과해도 다른 인스턴스가 먼저 커밋하면 @Version UPDATE가 실패하므로, 최종 방어선은 DB다.
	 */
	public static void check(Long requested, long current) {
		if (requested == null) {
			throw new BusinessException(CommonErrorCode.VALIDATION_FAILED,
					Map.of("fieldErrors", List.of(new FieldErrorDetail("version", ValidationReason.REQUIRED.name()))));
		}
		if (requested != current) {
			throw new BusinessException(CommonErrorCode.VERSION_CONFLICT, Map.of("currentVersion", current));
		}
	}

	/** {@link #checkUpsert} 결과. 호출자가 이 값에 따라 INSERT 또는 UPDATE를 한다. */
	public enum UpsertAction {
		CREATE, UPDATE
	}

	/**
	 * 생성·갱신 겸용 PUT(온보딩 임시 저장, 직접 회고)의 version 판정. 호출자가 users 행 잠금 안에서 기존 행을 읽은 뒤 부른다.
	 * 개별 수정 API의 {@link #check}는 version 누락을 400으로 처리하지만, 이 PUT은 첫 저장 때만 version 생략이 정상이다.
	 * 그래서 기존 행이 있는데 생략한 요청도 "오래된 화면의 저장"으로 보고 409로 최신 version을 알려 준다.
	 *
	 * 1. 행 없음 + version 생략 -> CREATE.
	 * 2. 행 없음 + version 있음 -> 409 currentVersion null(지워진 것을 이어 쓰려는 오래된 화면).
	 * 3. 행 있음 + version 생략 또는 다름 -> 409 currentVersion(현재 값).
	 * 4. 행 있음 + version 같음 -> UPDATE.
	 *
	 * @param currentVersion 기존 행의 version. 행이 없으면 null
	 */
	public static UpsertAction checkUpsert(Long currentVersion, Long requested) {
		if (currentVersion == null) {
			if (requested != null) {
				throw conflict(null);
			}
			return UpsertAction.CREATE;
		}
		if (requested == null || !currentVersion.equals(requested)) {
			throw conflict(currentVersion);
		}
		return UpsertAction.UPDATE;
	}

	/**
	 * 409 VERSION_CONFLICT. currentVersion이 null이면 "대상 행 없음"(명세 §4.2·§4.12)이다.
	 * Map.of는 null 값을 받지 않으므로 HashMap을 쓴다.
	 */
	public static BusinessException conflict(Long currentVersion) {
		Map<String, Object> details = new HashMap<>();
		details.put("currentVersion", currentVersion);
		return new BusinessException(CommonErrorCode.VERSION_CONFLICT, details);
	}

	/**
	 * 충돌로 롤백이 끝난 뒤 새 트랜잭션(newTx, REQUIRES_NEW)에서 최신 version을 읽는다. 실패한 트랜잭션의 영속성 컨텍스트는
	 * 오염돼 있어 그 안에서 다시 읽으면 실패하거나 낡은 값을 읽는다. translateConflict의 currentVersion 인자로 쓴다.
	 *
	 * @param read    최신 version을 읽는다(행이 없으면 null). 새 트랜잭션 안에서 실행된다.
	 * @param missing 행이 그 사이 사라졌을 때 던질 예외(목표는 404, 온보딩·회고는 currentVersion null 409)
	 */
	public static long latestVersion(TransactionTemplate newTx, Supplier<Long> read,
			Supplier<? extends BusinessException> missing) {
		Long version = newTx.execute(status -> read.get());
		if (version == null) {
			throw missing.get();
		}
		return version;
	}

	/**
	 * 커밋(flush) 시점의 version 충돌도 currentVersion을 채워 409로 바꾼다(공유 계약 §5).
	 * 두 요청이 같은 version을 읽어 사전 비교를 모두 통과하면 늦은 쪽의 UPDATE가 충돌한다.
	 * action은 트랜잭션 전체를 감싼 호출이어야 한다. 이 메서드는 트랜잭션 바깥에서 예외를 잡으므로
	 * 실패한 트랜잭션이 이미 끝난 뒤 currentVersion(새 트랜잭션에서 읽어야 함)으로 최신 값을 읽는다.
	 * 롤백된 트랜잭션 안에서 다시 읽으면 오염된 영속성 컨텍스트 때문에 실패하거나 낡은 값을 읽는다.
	 */
	public static <T> T translateConflict(Supplier<T> action, LongSupplier currentVersion) {
		try {
			return action.get();
		} catch (OptimisticLockingFailureException e) {
			throw new BusinessException(CommonErrorCode.VERSION_CONFLICT,
					Map.of("currentVersion", currentVersion.getAsLong()));
		}
	}
}
