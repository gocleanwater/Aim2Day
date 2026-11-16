package com.aim2day.goal.progress;

import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProgressLogRepository extends JpaRepository<GoalProgressLog, Long> {

	/** 최근 기록이 먼저. created_at은 초 단위라 같은 시각이면 id가 큰 쪽(나중에 쓴 쪽)이 먼저다. */
	List<GoalProgressLog> findByUserIdOrderByCreatedAtDescIdDesc(Long userId, Pageable pageable);
}
