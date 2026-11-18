package com.aim2day.goal.reports;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** 모든 조회가 user_id 조건을 포함한다. 다른 사용자의 회고는 "없음"과 같다. */
public interface PeriodReviewRepository extends JpaRepository<PeriodReview, Long> {

	Optional<PeriodReview> findByUserIdAndPeriodTypeAndPeriodValue(Long userId, String periodType, String periodValue);
}
