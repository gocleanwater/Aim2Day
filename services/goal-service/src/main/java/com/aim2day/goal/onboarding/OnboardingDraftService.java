package com.aim2day.goal.onboarding;

import com.aim2day.goal.common.exception.BusinessException;
import com.aim2day.goal.common.exception.CommonErrorCode;
import com.aim2day.goal.common.exception.FieldErrors;
import com.aim2day.goal.common.exception.ValidationReason;
import com.aim2day.goal.common.exception.VersionGuard;
import com.aim2day.goal.common.lock.UserRowLock;
import com.aim2day.goal.onboarding.dto.DraftResponse;
import com.aim2day.goal.onboarding.dto.PutDraftRequest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.nio.charset.StandardCharsets;
import com.aim2day.goal.settings.SettingsService;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 온보딩 임시 저장 조회·저장·삭제(API 명세 §4.2). 상태를 인스턴스 메모리에 두지 않고 모두 DB에서 읽는다.
 *
 * 저장·삭제는 트랜잭션의 첫 쿼리로 users 행을 잠근다(UserRowLock). 두 이유가 있다.
 * 1. 같은 사용자의 첫 저장 두 개가 서로 다른 인스턴스에서 동시에 오면 둘 다 "행 없음"을 보고 INSERT를 시도한다.
 *    잠금으로 한 줄로 세우면 늦은 쪽은 앞선 요청의 커밋을 보고 "이미 있는데 version 없음"으로 409가 된다
 *    (user_id UNIQUE 위반 500이 아니다).
 * 2. 온보딩 완료(OnboardingCompleteService)도 같은 users 행을 잠그므로, 완료와 저장·삭제가 서로 끼어들지 못한다.
 *    다른 탭이 완료로 임시 저장을 지운 뒤 오래된 탭의 저장이 되살리는 일은 version 규칙이 409로 막는다.
 */
@Service
public class OnboardingDraftService {

	/** draftData의 직렬화한 UTF-8 최대 바이트 수(API 명세 §4.2 "최대 64KB"). */
	public static final int MAX_DRAFT_BYTES = 65_536;
	private static final int MIN_STEP = 1;
	private static final int MAX_STEP = 4;

	private final OnboardingDraftRepository repository;
	private final UserRowLock userRowLock;
	private final SettingsService settingsService;
	private final TransactionTemplate tx;
	private final TransactionTemplate newTx;
	/** 조회 전용. REPEATABLE READ를 명시한다(다른 조회 서비스와 같다). */
	private final TransactionTemplate readTx;
	@PersistenceContext
	private EntityManager em;

	public OnboardingDraftService(OnboardingDraftRepository repository, UserRowLock userRowLock,
			SettingsService settingsService, PlatformTransactionManager txManager) {
		this.repository = repository;
		this.userRowLock = userRowLock;
		this.settingsService = settingsService;
		this.tx = new TransactionTemplate(txManager);
		this.newTx = new TransactionTemplate(txManager);
		this.newTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		this.readTx = new TransactionTemplate(txManager);
		this.readTx.setReadOnly(true);
		this.readTx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
	}

	/** 임시 저장이 없으면 null(응답 data: null). */
	public DraftResponse get(long userId) {
		return readTx.execute(status -> repository.findByUserId(userId).map(DraftResponse::of).orElse(null));
	}

	public DraftResponse put(long userId, PutDraftRequest req) {
		FieldErrors errors = new FieldErrors();
		if (req.currentStep() == null) {
			errors.add("currentStep", ValidationReason.REQUIRED);
		} else if (req.currentStep() < MIN_STEP || req.currentStep() > MAX_STEP) {
			errors.add("currentStep", ValidationReason.OUT_OF_RANGE);
		}
		// JSON null은 역직렬화가 null로 넘기므로 생략과 같이 REQUIRED다. 서버는 내용을 해석하지 않는다.
		if (req.draftData() == null) {
			errors.add("draftData", ValidationReason.REQUIRED);
		} else if (req.draftData().getBytes(StandardCharsets.UTF_8).length > MAX_DRAFT_BYTES) {
			errors.add("draftData", ValidationReason.TOO_LONG);
		}
		errors.throwIfAny();

		// user_settings 행을 확보한다(없으면 기본값으로 만든다). version 카운터 열이 그 행에 있다. 트랜잭션 밖에서 부른다.
		settingsService.userTime(userId);
		int step = req.currentStep();
		String data = req.draftData();
		// 사전 비교를 통과한 뒤 다른 인스턴스가 먼저 커밋하는 경합은 @Version UPDATE가 막고, 트랜잭션 바깥에서 409로
		// 바꾼다. users 행 잠금이 같은 사용자의 저장을 한 줄로 세우므로 실제로는 드물다. 쓰기 트랜잭션에는 격리 수준을
		// 지정하지 않는다(H2의 REPEATABLE READ는 낙관적 락 대신 H2 전용 예외를 낸다).
		return VersionGuard.translateConflict(() -> tx.execute(status -> {
			userRowLock.lock(userId);
			OnboardingDraft draft = repository.findByUserId(userId).orElse(null);
			if (draft == null) {
				if (req.version() != null) {
					// 임시 저장이 이미 없다(다른 탭이 온보딩을 끝내 지웠다). 오래된 탭이 되살리지 못하게 한다.
					throw conflict(null);
				}
				// 시작 version = 사용자별 카운터 + 1. 삭제(DELETE·완료) 뒤 다시 만들어도 예전 탭이 가진 어떤 version보다
				// 크므로 그 탭의 PUT은 409가 된다(이후 PUT은 +1). 카운터는 삭제해도 남는다.
				// @Version 엔티티는 시작값을 직접 줄 수 없어(값이 있으면 새 엔티티로 취급되지 않는다) 0으로 INSERT한 뒤
				// 같은 트랜잭션(users 잠금 안)에서 version만 바꾸고 refresh해 응답에 반영한다.
				OnboardingDraft created = repository.saveAndFlush(new OnboardingDraft(userId, step, data));
				long start = lastVersion(userId) + 1;
				em.createNativeQuery("UPDATE onboarding_drafts SET version = :v WHERE id = :id")
						.setParameter("v", start).setParameter("id", created.getId()).executeUpdate();
				recordVersion(userId, start);
				em.refresh(created);
				return DraftResponse.of(created);
			}
			// 이미 있는데 version을 생략했거나 다르면 어느 쪽이든 오래된 화면의 저장이다. 개별 수정 API의 "version 필수 400"과
			// 달리 첫 저장 때만 생략이 정상이므로, 생략도 충돌로 알려 최신 version을 알려 준다.
			if (req.version() == null || !draft.getVersion().equals(req.version())) {
				throw conflict(draft.getVersion());
			}
			draft.change(step, data);
			repository.saveAndFlush(draft);
			// DB가 갱신한 updated_at을 응답에 담으려고 다시 읽는다(엔티티는 UPDATE 후 생성값을 읽지 않는다).
			em.refresh(draft);
			// 카운터는 항상 현재 version 이상이어야 한다(삭제 뒤 재생성이 예전 version보다 커지는 근거).
			recordVersion(userId, draft.getVersion());
			return DraftResponse.of(draft);
		}), () -> currentVersionOrConflict(userId));
	}

	/** user_settings.last_onboarding_draft_version. users 행 잠금 안에서만 부른다(설정 행은 put 앞에서 확보). */
	private long lastVersion(long userId) {
		Object value = em.createNativeQuery("SELECT last_onboarding_draft_version FROM user_settings WHERE user_id = :u")
				.setParameter("u", userId).getSingleResult();
		return ((Number) value).longValue();
	}

	/** 직접 UPDATE라 설정의 version을 올리지 않는다(열려 있는 설정 화면이 409가 되지 않게). */
	private void recordVersion(long userId, long version) {
		em.createNativeQuery("UPDATE user_settings SET last_onboarding_draft_version = :v WHERE user_id = :u")
				.setParameter("v", version).setParameter("u", userId).executeUpdate();
	}

	/** 없어도 성공이다(멱등). users 행 잠금 뒤에 지워 같은 사용자의 저장·완료와 순서가 섞이지 않게 한다. */
	public void delete(long userId) {
		tx.executeWithoutResult(status -> {
			userRowLock.lock(userId);
			repository.deleteByUserId(userId);
		});
	}

	/** 롤백이 끝난 뒤 새 트랜잭션에서 최신 version을 읽는다. 그 사이 지워졌으면 currentVersion null 충돌이다. */
	private long currentVersionOrConflict(long userId) {
		Long version = newTx.execute(status -> repository.findByUserId(userId)
				.map(OnboardingDraft::getVersion).orElse(null));
		if (version == null) {
			throw conflict(null);
		}
		return version;
	}

	private static BusinessException conflict(Long currentVersion) {
		// Map.of는 null 값을 받지 않는다. 명세는 currentVersion: null을 "임시 저장 없음"으로 쓴다.
		Map<String, Object> details = new HashMap<>();
		details.put("currentVersion", currentVersion);
		return new BusinessException(CommonErrorCode.VERSION_CONFLICT, details);
	}
}
