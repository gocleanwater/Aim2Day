package com.aim2day.goal.goals.monthly;

import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * "보이는" 월간 목표는 자신과 부모 연간 목표가 모두 삭제되지 않았고 연간 목표가 현재 사용자 소유인 것이다
 * (ERD 5.1-4, API 명세 §2.5). 소유 확인을 조회 조건에 넣어 남의 목표와 숨겨진 목표가 "없음"과 구분되지 않게 한다.
 * 연간 목표는 exists 서브쿼리로 확인한다. 조인으로 쓰면 FOR UPDATE가 연간 목표 행까지 잠가
 * 연간 목표 쪽 잠금 순서(users -> 연간)와 얽힌다. 바깥 문장의 잠금은 서브쿼리 테이블에 번지지 않는다.
 */
public interface MonthlyGoalRepository extends JpaRepository<MonthlyGoal, Long> {

	String VISIBLE = "m.id = :id and m.deletedAt is null and exists (select 1 from YearlyGoal y "
			+ "where y.id = m.yearlyGoalId and y.userId = :userId and y.deletedAt is null)";

	@Query("select m from MonthlyGoal m where " + VISIBLE)
	Optional<MonthlyGoal> findVisible(@Param("id") Long id, @Param("userId") Long userId);

	/** 월간 목표 행 잠금(SELECT ... FOR UPDATE). 부모 확인과 잠금을 한 번에 하고, 그 뒤의 읽기는 최신 커밋 값을 본다. */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select m from MonthlyGoal m where " + VISIBLE)
	Optional<MonthlyGoal> findVisibleForUpdate(@Param("id") Long id, @Param("userId") Long userId);

	/** 정렬은 연간 목표의 displayOrder, 월간 id 순이다(API 명세 §4.5). */
	@Query("select m from MonthlyGoal m, YearlyGoal y where y.id = m.yearlyGoalId and y.userId = :userId "
			+ "and y.deletedAt is null and m.deletedAt is null and m.startDate = :startDate "
			+ "order by y.displayOrder asc, m.id asc")
	List<MonthlyGoal> findVisibleByMonth(@Param("userId") Long userId, @Param("startDate") LocalDate startDate);

	/** 연간 상세 트리용. 호출자가 연간 목표의 가시성을 이미 확인했다. */
	@Query("select m from MonthlyGoal m where m.yearlyGoalId = :yearlyGoalId and m.deletedAt is null "
			+ "order by m.startDate asc, m.id asc")
	List<MonthlyGoal> findActiveByYearlyGoal(@Param("yearlyGoalId") Long yearlyGoalId);

	/** 진행 기록·주간 응답의 경로 이름 조회용. 삭제된 목표의 이름도 보여 주므로 deleted_at 조건이 없다. */
	List<MonthlyGoal> findAllByIdIn(Collection<Long> ids);

	/**
	 * 서버가 계산한 진행률 저장. version을 올리지 않는다(ERD 결정 17). 영속성 컨텍스트를 비우지 않는다:
	 * 호출자가 들고 있는 엔티티를 분리하면 이후 refresh가 실패하기 때문이다(낡은 진행률은 읽기 전용 매핑이라 덮어쓰이지 않는다).
	 */
	@Modifying(flushAutomatically = true)
	@Query("update MonthlyGoal m set m.progressRate = :rate where m.id = :id")
	int updateProgressRate(@Param("id") Long id, @Param("rate") BigDecimal rate);

	String OWNED = "m.id = :id and m.deletedAt is not null and exists (select 1 from YearlyGoal y "
			+ "where y.id = m.yearlyGoalId and y.userId = :userId)";

	/** 복원용 행 잠금. 자신은 삭제 상태여야 하고, 조상의 삭제 여부는 보지 않는다(복원 API가 따로 409로 가른다). */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select m from MonthlyGoal m where " + OWNED)
	Optional<MonthlyGoal> findDeletedForUpdate(@Param("id") Long id, @Param("userId") Long userId);

	/** 휴지통 목록: (월간, 부모 연간) 쌍. 직접 삭제됐고 기한이 남은 것. 연간 소유자가 현재 사용자다. */
	@Query("select m, y from MonthlyGoal m, YearlyGoal y where y.id = m.yearlyGoalId and y.userId = :userId "
			+ "and m.deletedAt >= :cutoff")
	List<Object[]> findInTrash(@Param("userId") Long userId, @Param("cutoff") LocalDateTime cutoff);

	/** 삭제 확인 창의 하위 개수: 삭제되지 않은 월간 목표 수. */
	long countByYearlyGoalIdAndDeletedAtIsNull(Long yearlyGoalId);

	@Modifying(flushAutomatically = true)
	@Query("update MonthlyGoal m set m.deletedAt = :deletedAt where m.id = :id")
	int setDeletedAt(@Param("id") Long id, @Param("deletedAt") LocalDateTime deletedAt);
}
