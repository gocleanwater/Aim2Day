package com.aim2day.goal.onboarding;

import com.aim2day.goal.goals.yearly.YearlyGoalRepository;
import com.aim2day.goal.goals.yearly.YearlyGoalService;
import com.aim2day.goal.onboarding.dto.StatusResponse;
import com.aim2day.goal.settings.SettingsService;
import com.aim2day.goal.settings.UserTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** 온보딩 필요 여부(API 명세 §4.2 GET status). 읽기 전용이라 잠금 없이 한 스냅샷으로 읽는다. */
@Service
public class OnboardingStatusService {

	private static final String QUESTION_JANUARY = "올해 어떤 일에 집중하고 싶나요?";
	private static final String QUESTION_OTHER = "올해 남은 기간에 어떤 일에 집중하고 싶나요?";

	private final YearlyGoalRepository yearlyRepository;
	private final OnboardingDraftRepository draftRepository;
	private final SettingsService settingsService;
	private final TransactionTemplate readTx;

	public OnboardingStatusService(YearlyGoalRepository yearlyRepository, OnboardingDraftRepository draftRepository,
			SettingsService settingsService, PlatformTransactionManager txManager) {
		this.yearlyRepository = yearlyRepository;
		this.draftRepository = draftRepository;
		this.settingsService = settingsService;
		this.readTx = new TransactionTemplate(txManager);
		this.readTx.setReadOnly(true);
		this.readTx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
	}

	public StatusResponse status(long userId) {
		// "올해"와 "1월 여부"는 계정 시간대 기준이다. 트랜잭션 밖에서 먼저 읽는다.
		UserTime time = settingsService.userTime(userId);
		int year = time.today().getYear();
		// 활성 개수와 임시 저장 여부를 한 스냅샷으로 읽는다. 따로 읽으면 그 사이 온보딩 완료가 끼어
		// "목표 0개인데 임시 저장 없음" 같은 어긋난 조합이 나올 수 있다.
		return readTx.execute(status -> {
			// 활성은 상태(PROGRESS·COMPLETED 등)와 무관하게 소프트 삭제되지 않은 목표다.
			long active = yearlyRepository.countByUserIdAndTargetYearAndDeletedAtIsNull(userId, year);
			boolean hasDraft = draftRepository.existsByUserId(userId);
			String question = time.today().getMonthValue() == 1 ? QUESTION_JANUARY : QUESTION_OTHER;
			return new StatusResponse(active == 0 || hasDraft, hasDraft, year, (int) active,
					YearlyGoalService.MAX_ACTIVE_PER_YEAR, question);
		});
	}
}
