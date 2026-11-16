package com.aim2day.goal.goals.criteria;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CriterionRepository extends JpaRepository<YearlyGoalCriterion, Long> {

	List<YearlyGoalCriterion> findByYearlyGoalIdOrderByIdAsc(Long yearlyGoalId);

	/** 목록 화면용: 여러 목표의 기준을 한 번에 읽어 목표 수만큼 쿼리가 나가는 것(N+1)을 피한다. */
	List<YearlyGoalCriterion> findByYearlyGoalIdIn(Collection<Long> yearlyGoalIds);

	/** 기준이 이 목표의 것인지 함께 확인한다. 남의 목표·내 다른 목표의 기준 id는 "없음"과 같다. */
	Optional<YearlyGoalCriterion> findByIdAndYearlyGoalId(Long id, Long yearlyGoalId);
}
