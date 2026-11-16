package com.aim2day.goal.common.lock;

import com.aim2day.goal.common.exception.BusinessException;
import com.aim2day.goal.common.exception.CommonErrorCode;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 사용자 행 잠금(ERD §4.1). 같은 사용자의 목표 생성·순서 변경·기준 변경을 DB 수준에서 한 줄로 세운다.
 * 인스턴스 3개가 각자 "활성 목표 수 < 3"을 확인하고 동시에 INSERT하면 한도를 넘을 수 있으므로,
 * 인스턴스 메모리 잠금이 아니라 users 행의 FOR UPDATE로 직렬화한다.
 * 반드시 트랜잭션의 첫 쿼리로 호출한다. MySQL REPEATABLE READ에서 일반 SELECT가 먼저 나가면 그 시점의
 * 스냅샷이 고정되어, 잠금을 얻은 뒤에도 다른 요청이 커밋한 목표를 세지 못한다.
 * 잠금 순서는 항상 users -> 목표(연간 -> 월간/주간)다. 이 순서를 어기면 교착이 생긴다.
 */
@Component
public class UserRowLock {

	@PersistenceContext
	private EntityManager em;

	public void lock(long userId) {
		if (!TransactionSynchronizationManager.isActualTransactionActive()) {
			// 트랜잭션 밖의 FOR UPDATE는 즉시 풀려 아무것도 보호하지 못한다. 호출 실수를 바로 드러낸다.
			throw new IllegalStateException("UserRowLock.lock은 트랜잭션 안에서만 호출할 수 있다");
		}
		var rows = em.createNativeQuery("SELECT id FROM users WHERE id = :id FOR UPDATE")
				.setParameter("id", userId)
				.getResultList();
		if (rows.isEmpty()) {
			// 세션은 유효하지만 계정이 사라진 경우(탈퇴 등). 목표를 만들 주체가 없으므로 인증되지 않은 것으로 본다.
			throw new BusinessException(CommonErrorCode.UNAUTHENTICATED);
		}
	}
}
