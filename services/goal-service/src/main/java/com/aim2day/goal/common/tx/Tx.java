package com.aim2day.goal.common.tx;

import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 서비스들이 쓰는 TransactionTemplate 설정 모음. 호출마다 새 템플릿을 만들어 돌려주므로 서비스끼리 설정이 섞이지 않는다.
 * 어떤 템플릿을 쓰는지(쓰기·독립·읽기)가 곧 전파·격리 정책이라, 설정을 바꿀 때는 이 파일 한 곳만 본다.
 */
public final class Tx {

	private Tx() {
	}

	/**
	 * 기본 쓰기 트랜잭션. 격리 수준을 지정하지 않는다(MySQL 기본 REPEATABLE READ를 따른다).
	 * H2의 REPEATABLE READ는 낙관적 락 대신 H2 전용 예외를 내므로 쓰기에는 명시하지 않는다.
	 */
	public static TransactionTemplate write(PlatformTransactionManager txManager) {
		return new TransactionTemplate(txManager);
	}

	/** 호출자 트랜잭션과 무관한 독립 트랜잭션(PROPAGATION_REQUIRES_NEW). 롤백된 뒤 최신 값 읽기, 유니크 위반 가능 생성 등에 쓴다. */
	public static TransactionTemplate requiresNew(PlatformTransactionManager txManager) {
		TransactionTemplate template = new TransactionTemplate(txManager);
		template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		return template;
	}

	/** 읽기 전용 + REPEATABLE READ. 한 요청 안의 여러 조회가 같은 스냅샷을 보게 한다. */
	public static TransactionTemplate readOnly(PlatformTransactionManager txManager) {
		TransactionTemplate template = new TransactionTemplate(txManager);
		template.setReadOnly(true);
		template.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
		return template;
	}

	/** 읽기 전용 + 독립 트랜잭션(격리 수준은 지정하지 않는다). 예약 작업이 root 목록을 읽을 때 쓴다. */
	public static TransactionTemplate readOnlyRequiresNew(PlatformTransactionManager txManager) {
		TransactionTemplate template = new TransactionTemplate(txManager);
		template.setReadOnly(true);
		template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		return template;
	}
}
