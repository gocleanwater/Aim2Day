package com.aim2day.goal.goals.criteria;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 연간 목표 달성 기준(yearly_goal_criteria) 테이블 접근 인터페이스.
 * 기준에는 user_id 컬럼이 없어, 호출자가 연간 목표의 소유권을 먼저 확인한 뒤 연간 목표 id로 조회한다.
 */
public interface CriterionRepository extends JpaRepository<YearlyGoalCriterion, Long> {

	/**
	 * 한 연간 목표의 기준 전체를 id 오름차순(입력 순서)으로 찾는다.
	 * 반환: 기준 목록, 없으면 빈 목록.
	 */
	List<YearlyGoalCriterion> findByYearlyGoalIdOrderByIdAsc(Long yearlyGoalId);

	/** 목록 화면용: 여러 목표의 기준을 한 번에 읽어 목표 수만큼 쿼리가 나가는 것(N+1)을 피한다. */
	List<YearlyGoalCriterion> findByYearlyGoalIdIn(Collection<Long> yearlyGoalIds);

	/** 기준이 이 목표의 것인지 함께 확인한다. 남의 목표·내 다른 목표의 기준 id는 "없음"과 같다. */
	Optional<YearlyGoalCriterion> findByIdAndYearlyGoalId(Long id, Long yearlyGoalId);
}
