package com.aim2day.goal.goals.weekly.record;

import java.util.List;
import java.util.Optional;
import java.math.BigDecimal;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 수치 기록(numeric_records) 테이블 접근 인터페이스.
 * 기록에는 user_id가 없으므로, 호출자가 주간 목표의 소유권을 확인한 뒤 주간 목표 id로 조회한다.
 */
public interface NumericRecordRepository extends JpaRepository<NumericRecord, Long> {

	/** 최근 기록부터. 같은 시각(초 단위)에 만든 기록은 id가 큰 쪽이 나중이다. */
	List<NumericRecord> findByWeeklyGoalIdOrderByCreatedAtDescIdDesc(Long weeklyGoalId);

	/** 기록이 이 주간 목표의 것인지까지 조회 조건에 넣어, 다른 목표의 기록 id로는 접근하지 못하게 한다. */
	Optional<NumericRecord> findByIdAndWeeklyGoalId(Long id, Long weeklyGoalId);

	/** 행동 완료로 생긴 기록(행동당 최대 1행, action_id UNIQUE). */
	Optional<NumericRecord> findByActionId(Long actionId);

	/**
	 * 완료된 행동의 기여량·주간 목표 변경에 맞춰 기록을 고친다(ERD 8번 규칙). 기록은 엔티티에서 읽기 전용이라
	 * 직접 UPDATE로 바꾼다. 영속성 컨텍스트를 비우지 않는다.
	 */
	@Modifying(flushAutomatically = true)
	@Query("update NumericRecord r set r.weeklyGoalId = :weeklyGoalId, r.amount = :amount where r.id = :id")
	int updateWeeklyAndAmount(@Param("id") Long id, @Param("weeklyGoalId") Long weeklyGoalId,
			@Param("amount") BigDecimal amount);
}
