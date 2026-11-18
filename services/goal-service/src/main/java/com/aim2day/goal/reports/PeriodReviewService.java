package com.aim2day.goal.reports;

import com.aim2day.goal.common.exception.BusinessException;
import com.aim2day.goal.common.exception.CommonErrorCode;
import com.aim2day.goal.common.exception.FieldErrors;
import com.aim2day.goal.common.exception.ValidationReason;
import com.aim2day.goal.common.exception.VersionGuard;
import com.aim2day.goal.common.lock.UserRowLock;
import com.aim2day.goal.reports.dto.PutReviewRequest;
import com.aim2day.goal.reports.dto.ReviewResponse;
import com.aim2day.goal.settings.SettingsService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 직접 회고 저장(API 명세 §4.12 PUT). 서버는 사용자가 쓴 내용만 저장하고 바꾸지 않는다.
 *
 * version 규칙은 온보딩 임시 저장(OnboardingDraftService)과 같다. 다른 점은 회고는 삭제 API가 없다는 것 하나다.
 * 삭제가 없으면 "예전 탭이 가진 version으로 새로 만든 행을 덮어쓰는" 문제가 생기지 않으므로 version 카운터가 필요 없다.
 * 1. 행이 없고 version을 생략 -> 생성(version 1).
 * 2. 행이 있고 version을 생략하거나 다름 -> 409 currentVersion(현재 값). 오래된 화면의 저장이다.
 * 3. 행이 없는데 version을 보냄 -> 409 currentVersion null.
 * 4. 행이 있고 version이 같음 -> 갱신.
 *
 * 쓰기는 트랜잭션의 첫 쿼리로 users 행을 잠가 같은 사용자의 저장을 DB 수준에서 한 줄로 세운다. 두 인스턴스가 같은
 * 기간의 첫 저장을 동시에 보내도 늦은 쪽은 앞선 요청의 커밋을 보고 "있는데 version 없음"(규칙 2)으로 409가 된다
 * (UNIQUE 위반 500이 아니다).
 */
@Service
public class PeriodReviewService {

	/** improvements·next_focus 각각의 최대 글자 수(코드 포인트). API 명세 §4.12. */
	public static final int MAX_LENGTH = 5_000;
	/** 처음 저장했을 때의 version. 온보딩 임시 저장의 첫 version(1)과 맞춘다. */
	private static final long FIRST_VERSION = 1L;

	private final PeriodReviewRepository repository;
	private final UserRowLock userRowLock;
	private final SettingsService settingsService;
	private final TransactionTemplate tx;
	private final TransactionTemplate newTx;
	@PersistenceContext
	private EntityManager em;

	public PeriodReviewService(PeriodReviewRepository repository, UserRowLock userRowLock,
			SettingsService settingsService, PlatformTransactionManager txManager) {
		this.repository = repository;
		this.userRowLock = userRowLock;
		this.settingsService = settingsService;
		this.tx = new TransactionTemplate(txManager);
		this.newTx = new TransactionTemplate(txManager);
		this.newTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
	}

	public ReviewResponse put(long userId, String periodType, String period, PutReviewRequest req) {
		// 검사 순서: 형식 400(경로, 본문) -> 미래 기간 422 -> (잠금 안) version 409.
		ReportPeriod p = ReportPeriod.parse(periodType, period);
		FieldErrors errors = new FieldErrors();
		checkLength("improvements", req.improvements(), errors);
		checkLength("nextFocus", req.nextFocus(), errors);
		errors.throwIfAny();
		// 오늘(계정 시간대)은 SettingsService가 자기 트랜잭션을 쓰므로 트랜잭션 밖에서 구한다.
		LocalDate today = settingsService.userTime(userId).today();
		if (p.isFuture(today)) {
			throw new BusinessException(ReportErrorCode.REPORT_INVALID_PERIOD);
		}
		String type = p.type().name();
		// 사전 비교를 통과한 뒤 다른 인스턴스가 먼저 커밋하는 경합은 @Version UPDATE가 막고 트랜잭션 바깥에서 409로 바꾼다.
		// 쓰기 트랜잭션에는 격리 수준을 지정하지 않는다(H2의 REPEATABLE READ는 낙관적 락 대신 H2 전용 예외를 낸다).
		return VersionGuard.translateConflict(() -> tx.execute(status -> {
			userRowLock.lock(userId);
			PeriodReview review = repository.findByUserIdAndPeriodTypeAndPeriodValue(userId, type, p.value())
					.orElse(null);
			if (review == null) {
				if (req.version() != null) {
					// 행이 없는데 version을 보냈다. 있지도 않은 회고를 이어 쓰려는 화면이므로 거부한다.
					throw conflict(null);
				}
				PeriodReview created = repository.saveAndFlush(
						new PeriodReview(userId, p.type(), p.value(), req.improvements(), req.nextFocus()));
				// @Version 엔티티는 시작값을 직접 줄 수 없어 0으로 INSERT한 뒤 같은 트랜잭션(users 잠금 안)에서 1로 맞추고 refresh한다.
				em.createNativeQuery("UPDATE period_reviews SET version = :v WHERE id = :id")
						.setParameter("v", FIRST_VERSION).setParameter("id", created.getId()).executeUpdate();
				em.refresh(created);
				return ReviewResponse.of(created);
			}
			// 있는데 생략하거나 다르면 어느 쪽이든 오래된 화면의 저장이다. 첫 저장만 생략이 정상이라, 개별 수정 API의
			// "version 필수 400"과 달리 생략도 409로 알려 최신 version을 준다.
			if (req.version() == null || !review.getVersion().equals(req.version())) {
				throw conflict(review.getVersion());
			}
			review.change(req.improvements(), req.nextFocus());
			repository.saveAndFlush(review);
			// UPDATE로 오른 version을 응답에 담으려고 다시 읽는다(엔티티가 flush한 값과 같지만 DB 기준으로 확정한다).
			em.refresh(review);
			return ReviewResponse.of(review);
		}), () -> currentVersionOrConflict(userId, type, p.value()));
	}

	private static void checkLength(String field, String value, FieldErrors errors) {
		if (value != null && value.codePointCount(0, value.length()) > MAX_LENGTH) {
			errors.add(field, ValidationReason.TOO_LONG);
		}
	}

	/** 롤백이 끝난 뒤 새 트랜잭션에서 최신 version을 읽는다. 회고는 지우지 않지만 방어적으로 없으면 null 충돌이다. */
	private long currentVersionOrConflict(long userId, String type, String value) {
		Long version = newTx.execute(status -> repository.findByUserIdAndPeriodTypeAndPeriodValue(userId, type, value)
				.map(PeriodReview::getVersion).orElse(null));
		if (version == null) {
			throw conflict(null);
		}
		return version;
	}

	private static BusinessException conflict(Long currentVersion) {
		// Map.of는 null 값을 받지 않는다. 명세는 currentVersion: null을 "회고 없음"으로 쓴다.
		Map<String, Object> details = new HashMap<>();
		details.put("currentVersion", currentVersion);
		return new BusinessException(CommonErrorCode.VERSION_CONFLICT, details);
	}
}
