package com.aim2day.goal.common.exception;

import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.springframework.dao.OptimisticLockingFailureException;

/**
 * 수정 요청의 version과 DB의 현재 version을 비교한다(API 명세 §2.4).
 * 서비스가 JPA 갱신 전에 먼저 호출해 409 VERSION_CONFLICT의 details.currentVersion을 채운다.
 * Hibernate의 OptimisticLockException만으로는 최신 version을 알 수 없어서다.
 * 비교를 통과한 뒤 다른 인스턴스가 먼저 커밋한 경합은 @Version UPDATE가 막는다(GlobalExceptionHandler 안전망).
 */
public final class VersionGuard {

	private VersionGuard() {
	}

	public static void check(Long requested, long current) {
		if (requested == null) {
			throw new BusinessException(CommonErrorCode.VALIDATION_FAILED,
					Map.of("fieldErrors", List.of(new FieldErrorDetail("version", ValidationReason.REQUIRED.name()))));
		}
		if (requested != current) {
			throw new BusinessException(CommonErrorCode.VERSION_CONFLICT, Map.of("currentVersion", current));
		}
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
