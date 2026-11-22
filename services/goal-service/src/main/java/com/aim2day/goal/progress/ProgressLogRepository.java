package com.aim2day.goal.progress;

import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 진행 기록(goal_progress_logs) 테이블 접근 인터페이스.
 * 모든 조회는 user_id 조건을 포함해 다른 사용자의 기록이 보이지 않게 한다.
 */
public interface ProgressLogRepository extends JpaRepository<GoalProgressLog, Long> {

	/** 최근 기록이 먼저. created_at은 초 단위라 같은 시각이면 id가 큰 쪽(나중에 쓴 쪽)이 먼저다. */
	List<GoalProgressLog> findByUserIdOrderByCreatedAtDescIdDesc(Long userId, Pageable pageable);

	/**
	 * 리포트용: 연간 목표들의 진행 기록 전체를 저장 순서(id)로 한 번에 읽는다. 시간대 변경으로 log_date가 앞 기록보다
	 * 이를 수 있어 날짜 정렬·날짜 상한을 걸지 않고, "일어난 순서"(id)를 기준으로 호출자가 유효 날짜를 계산한다.
	 */
	@Query("select l from GoalProgressLog l where l.userId = :userId and l.targetType = "
			+ "com.aim2day.goal.progress.ProgressTargetType.YEARLY_GOAL and l.targetId in :ids order by l.id asc")
	List<GoalProgressLog> findYearlyLogs(@Param("userId") Long userId, @Param("ids") Collection<Long> ids);
}
