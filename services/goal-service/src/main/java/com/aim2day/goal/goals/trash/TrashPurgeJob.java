package com.aim2day.goal.goals.trash;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 30일 정리 예약 작업. 세 인스턴스가 모두 같은 cron으로 이 메서드를 부르지만, 중복 처리는 TrashPurgeService가
 * users 행 잠금과 FOR UPDATE SKIP LOCKED로 막는다(인스턴스 메모리나 리더 선출에 의존하지 않는다).
 *
 * cron은 환경 변수 AIM2DAY_TRASH_PURGE_CRON(= aim2day.trash.purge-cron)으로 바꾼다. 기본값은 매시 정각이다.
 * aim2day.trash.purge-enabled=false이면 이 설정 전체(스케줄링 활성화 포함)가 빠진다. 테스트 컨텍스트가 쓴다.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "aim2day.trash.purge-enabled", havingValue = "true", matchIfMissing = true)
public class TrashPurgeJob {

	private final TrashPurgeService service;

	public TrashPurgeJob(TrashPurgeService service) {
		this.service = service;
	}

	@Scheduled(cron = "${aim2day.trash.purge-cron:0 0 * * * *}")
	public void run() {
		service.purgeExpired();
	}
}
