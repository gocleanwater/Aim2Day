package com.aim2day.goal.goals.weekly.record;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NumericRecordRepository extends JpaRepository<NumericRecord, Long> {

	/** 최근 기록부터. 같은 시각(초 단위)에 만든 기록은 id가 큰 쪽이 나중이다. */
	List<NumericRecord> findByWeeklyGoalIdOrderByCreatedAtDescIdDesc(Long weeklyGoalId);

	/** 기록이 이 주간 목표의 것인지까지 조회 조건에 넣어, 다른 목표의 기록 id로는 접근하지 못하게 한다. */
	Optional<NumericRecord> findByIdAndWeeklyGoalId(Long id, Long weeklyGoalId);
}
