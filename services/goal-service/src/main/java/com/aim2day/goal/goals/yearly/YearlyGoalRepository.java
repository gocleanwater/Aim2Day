package com.aim2day.goal.goals.yearly;

import jakarta.persistence.LockModeType;
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
 * 모든 조회는 user_id와 deleted_at IS NULL 조건을 함께 건다. 다른 사용자의 목표와 삭제된 목표는
 * "없음"과 구분되지 않아야 하므로(API 명세 §2.5) 소유권 확인을 조회 조건에 넣는다.
 * 직접 UPDATE는 @Version을 건드리지 않는 것이 기본이라, version이 오르는 쪽만 명시적으로 version + 1을 쓴다.
 * clearAutomatically: 직접 UPDATE는 영속성 컨텍스트를 거치지 않으므로, 이미 읽은 엔티티가 낡은 값을
 * 들고 있지 않게 컨텍스트를 비운다(호출한 서비스가 필요하면 다시 읽는다).
 */
public interface YearlyGoalRepository extends JpaRepository<YearlyGoal, Long> {

	Optional<YearlyGoal> findByIdAndUserIdAndDeletedAtIsNull(Long id, Long userId);

	/** 연간 목표 행 잠금(SELECT ... FOR UPDATE). 기준 저장·확정·달성을 한 줄로 세운다. */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select g from YearlyGoal g where g.id = :id and g.userId = :userId and g.deletedAt is null")
	Optional<YearlyGoal> findOwnedForUpdate(@Param("id") Long id, @Param("userId") Long userId);

	List<YearlyGoal> findByUserIdAndTargetYearAndDeletedAtIsNullOrderByDisplayOrderAscIdAsc(Long userId, int targetYear);

	long countByUserIdAndTargetYearAndDeletedAtIsNull(Long userId, int targetYear);

	@Query("select max(g.displayOrder) from YearlyGoal g where g.userId = :userId and g.targetYear = :year and g.deletedAt is null")
	Integer findMaxDisplayOrder(@Param("userId") Long userId, @Param("year") int year);

	/**
	 * 리포트의 목표별 달성률 변화 대상. 영구 삭제되지 않은 연간 목표라면 소프트 삭제된 것도 포함하므로 deleted_at 조건이 없다.
	 * 소유 확인은 user_id 조건이다.
	 */
	List<YearlyGoal> findByUserIdAndTargetYearOrderByDisplayOrderAscIdAsc(Long userId, int targetYear);

	/** 진행 기록의 대상 제목 조회용. 삭제된 목표의 기록도 제목과 함께 보여 주므로 deleted_at 조건이 없다. */
	List<YearlyGoal> findAllByIdIn(Collection<Long> ids);

	/** 카드 순서 저장. 사용자가 고친 필드가 아니므로 version을 올리지 않는다(API 명세 §2.4). */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("update YearlyGoal g set g.displayOrder = :displayOrder where g.id = :id and g.userId = :userId")
	int updateDisplayOrder(@Param("id") Long id, @Param("userId") Long userId, @Param("displayOrder") int displayOrder);

	/** 초안 저장: 목표 필드는 그대로지만 기준 목록이 바뀌었으므로 version만 올린다(API 명세 §4.4). */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("update YearlyGoal g set g.version = g.version + 1 where g.id = :id")
	int bumpVersion(@Param("id") Long id);

	/** 확정 후 개정: 최종 개정 시각을 갱신하고 version을 올린다. 최초 확정 시각은 건드리지 않는다. */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("update YearlyGoal g set g.criteriaLastRevisedAt = :now, g.version = g.version + 1 where g.id = :id")
	int markCriteriaRevised(@Param("id") Long id, @Param("now") LocalDateTime now);

	/** 최초 확정: 기준선과 확정 시각을 저장한다. 최초 확정 시각과 최종 개정 시각은 같은 값이다. */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("update YearlyGoal g set g.criteriaConfirmed = true, g.initialBaselineRate = :baseline, "
			+ "g.criteriaConfirmedAt = :now, g.criteriaLastRevisedAt = :now, g.version = g.version + 1 where g.id = :id")
	int confirmCriteria(@Param("id") Long id, @Param("baseline") int baseline, @Param("now") LocalDateTime now);

	/**
	 * 복원·삭제 상태 확인용 행 잠금. findOwnedForUpdate와 반대로 deleted_at이 있는 행만 읽는다.
	 * 삭제된 목표는 일반 조회에서 404이므로 복원 API만 이 쿼리를 쓴다. 소유 확인은 user_id 조건이다.
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select g from YearlyGoal g where g.id = :id and g.userId = :userId and g.deletedAt is not null")
	Optional<YearlyGoal> findDeletedForUpdate(@Param("id") Long id, @Param("userId") Long userId);

	/** 휴지통 목록: 직접 삭제됐고 기한(cutoff 이후 삭제)이 남은 연간 목표. cutoff = 지금 - 30일. */
	@Query("select g from YearlyGoal g where g.userId = :userId and g.deletedAt >= :cutoff")
	List<YearlyGoal> findInTrash(@Param("userId") Long userId, @Param("cutoff") LocalDateTime cutoff);

	/** 삭제·복원 표시. 하위 행과 version은 건드리지 않는다(API 명세 §4.7: 연결과 데이터는 바꾸지 않는다). 컨텍스트는 호출자가 비운다. */
	@Modifying(flushAutomatically = true)
	@Query("update YearlyGoal g set g.deletedAt = :deletedAt where g.id = :id")
	int setDeletedAt(@Param("id") Long id, @Param("deletedAt") LocalDateTime deletedAt);
}
