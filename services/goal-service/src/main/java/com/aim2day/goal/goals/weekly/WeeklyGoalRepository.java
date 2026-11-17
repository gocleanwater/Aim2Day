package com.aim2day.goal.goals.weekly;

import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * "보이는" 주간 목표는 자신·월간·연간 목표가 모두 삭제되지 않았고 연간 목표가 현재 사용자 소유인 것이다.
 * 소유 확인은 exists 서브쿼리로 한다(MonthlyGoalRepository와 같은 이유: FOR UPDATE가 상위 행을 잠그지 않게).
 */
public interface WeeklyGoalRepository extends JpaRepository<WeeklyGoal, Long> {

	String VISIBLE = "w.id = :id and w.deletedAt is null and exists (select 1 from MonthlyGoal m, YearlyGoal y "
			+ "where m.id = w.monthlyGoalId and m.deletedAt is null and y.id = m.yearlyGoalId "
			+ "and y.userId = :userId and y.deletedAt is null)";

	@Query("select w from WeeklyGoal w where " + VISIBLE)
	Optional<WeeklyGoal> findVisible(@Param("id") Long id, @Param("userId") Long userId);

	/** 주간 목표 행 잠금. 잠금 순서(users -> 주간 -> 월간)에서 주간 단계다. */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select w from WeeklyGoal w where " + VISIBLE)
	Optional<WeeklyGoal> findVisibleForUpdate(@Param("id") Long id, @Param("userId") Long userId);

	/** date가 기간에 드는 주간 목표. 월간 정렬(연간 displayOrder, 월간 id) 뒤에 주간 id 순이다(API 명세 §4.6). */
	@Query("select w from WeeklyGoal w, MonthlyGoal m, YearlyGoal y where m.id = w.monthlyGoalId "
			+ "and y.id = m.yearlyGoalId and y.userId = :userId and y.deletedAt is null and m.deletedAt is null "
			+ "and w.deletedAt is null and w.startDate <= :date and w.endDate >= :date "
			+ "order by y.displayOrder asc, m.id asc, w.id asc")
	List<WeeklyGoal> findVisibleByDate(@Param("userId") Long userId, @Param("date") LocalDate date);

	/**
	 * 월간 목표 아래에서 date가 기간에 드는 삭제되지 않은 주간 목표(행동 연결 후보). 호출자가 월간 목표의 가시성을
	 * 이미 확인했다. id 순이라 후보 목록의 순서가 안정적이다.
	 */
	@Query("select w from WeeklyGoal w where w.monthlyGoalId = :monthlyId and w.deletedAt is null "
			+ "and w.startDate <= :date and w.endDate >= :date order by w.id asc")
	List<WeeklyGoal> findActiveByMonthlyContaining(@Param("monthlyId") Long monthlyId, @Param("date") LocalDate date);

	/** 기간이 [start, end]와 하루라도 겹치는 보이는 주간 목표(직전 주 요약). 정렬은 findVisibleByDate와 같다. */
	@Query("select w from WeeklyGoal w, MonthlyGoal m, YearlyGoal y where m.id = w.monthlyGoalId "
			+ "and y.id = m.yearlyGoalId and y.userId = :userId and y.deletedAt is null and m.deletedAt is null "
			+ "and w.deletedAt is null and w.startDate <= :end and w.endDate >= :start "
			+ "order by y.displayOrder asc, m.id asc, w.id asc")
	List<WeeklyGoal> findVisibleOverlapping(@Param("userId") Long userId, @Param("start") LocalDate start,
			@Param("end") LocalDate end);

	/** 월간 상세·연간 상세 트리용. 호출자가 상위 목표의 가시성을 이미 확인했다. */
	@Query("select w from WeeklyGoal w where w.monthlyGoalId in :monthlyIds and w.deletedAt is null "
			+ "order by w.startDate asc, w.id asc")
	List<WeeklyGoal> findActiveByMonthlyGoals(@Param("monthlyIds") Collection<Long> monthlyIds);

	/** 진행 기록 대상 이름 조회용. 삭제된 목표의 이름도 보여 주므로 deleted_at 조건이 없다. */
	List<WeeklyGoal> findAllByIdIn(Collection<Long> ids);

	/** 월간 목표별 삭제되지 않은 주간 목표 수. */
	@Query("select w.monthlyGoalId, count(w) from WeeklyGoal w where w.monthlyGoalId in :monthlyIds "
			+ "and w.deletedAt is null group by w.monthlyGoalId")
	List<Object[]> countActiveByMonthlyGoals(@Param("monthlyIds") Collection<Long> monthlyIds);

	/**
	 * current_value를 DB에서 원자적으로 더한다(ERD 결정 16). Java에서 읽어 더한 값을 쓰면 두 인스턴스가
	 * 같은 값을 읽어 한쪽 기여량이 사라진다. version은 올리지 않는다(결정 17).
	 * 영속성 컨텍스트를 비우지 않는다(MonthlyGoalRepository.updateProgressRate와 같은 이유).
	 */
	@Modifying(flushAutomatically = true)
	@Query("update WeeklyGoal w set w.currentValue = w.currentValue + :delta where w.id = :id")
	int addCurrentValue(@Param("id") Long id, @Param("delta") BigDecimal delta);

	@Modifying(flushAutomatically = true)
	@Query("update WeeklyGoal w set w.progressRate = :rate where w.id = :id")
	int updateProgressRate(@Param("id") Long id, @Param("rate") BigDecimal rate);
}
