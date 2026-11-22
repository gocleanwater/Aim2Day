package com.aim2day.goal.reports;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** 모든 조회가 user_id 조건을 포함한다. 다른 사용자의 회고는 "없음"과 같다. */
public interface PeriodReviewRepository extends JpaRepository<PeriodReview, Long> {

	/**
	 * 사용자의 한 기간(유형+값)에 대한 직접 회고를 찾는다. 사용자·기간당 최대 한 행(UNIQUE)이다.
	 * 반환: 회고, 아직 쓰지 않았으면 빈 Optional.
	 */
	Optional<PeriodReview> findByUserIdAndPeriodTypeAndPeriodValue(Long userId, String periodType, String periodValue);
}
