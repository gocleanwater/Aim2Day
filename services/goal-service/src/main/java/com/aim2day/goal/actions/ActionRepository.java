package com.aim2day.goal.actions;

import jakarta.persistence.LockModeType;
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
 * 행동 조회 규칙
 * - 쓰기 대상(수정·완료·삭제)은 자신이 삭제되지 않았고, 목표 연결 행동이면 경로(주간·월간·연간)가 모두 삭제되지
 *   않은 것이다(PATH_VISIBLE). 경로가 삭제된 행동은 완료 여부와 관계없이 404다. 숨은 부모의 진행률을 바꾸지 않기 위해서다.
 * - 목록·조회는 자신이 삭제되지 않았고, "경로가 삭제된 행동"은 완료된 것만 남긴다(미완료는 숨긴다, API 명세 §4.9).
 * 소유 확인은 a.userId로 한다. 경로 검사는 exists 서브쿼리다(FOR UPDATE가 상위 행을 잠그지 않게, 다른 Repository와 같은 이유).
 */
public interface ActionRepository extends JpaRepository<Action, Long> {

	/** 행동이 연결된 경로(주간·월간·연간 목표)가 모두 삭제되지 않았고 같은 사용자 소유인지 확인하는 JPQL 조각. */
	String PATH_VISIBLE = "exists (select 1 from WeeklyGoal w, MonthlyGoal m, YearlyGoal y "
			+ "where w.id = a.weeklyGoalId and w.deletedAt is null and m.id = w.monthlyGoalId and m.deletedAt is null "
			+ "and y.id = m.yearlyGoalId and y.deletedAt is null and y.userId = a.userId)";

	/** 수정·완료·삭제 대상 조건: 내 행동이고 삭제되지 않았으며, 목표 연결 행동이면 경로도 보인다. */
	String WRITABLE = "a.id = :id and a.userId = :userId and a.deletedAt is null "
			+ "and (a.weeklyGoalId is null or " + PATH_VISIBLE + ")";

	/** 목록·조회 대상 조건: 내 행동이고 삭제되지 않았으며, 경로가 삭제된 행동은 완료된 것만 남긴다. */
	String LISTED = "a.userId = :userId and a.deletedAt is null "
			+ "and (a.weeklyGoalId is null or a.completed = true or " + PATH_VISIBLE + ")";

	/** 행동 행 잠금(SELECT ... FOR UPDATE). 잠금 순서(users -> 행동 -> 주간 -> 월간)에서 행동 단계이고, 상태는 잠근 뒤에 다시 읽는다. */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select a from Action a where " + WRITABLE)
	Optional<Action> findWritableForUpdate(@Param("id") Long id, @Param("userId") Long userId);

	/** 수행일이 date인 행동. 표시 순서는 정렬기가 정하고 여기서는 id 순이다(FOCUS-015의 "기존 입력 순서"). */
	@Query("select a from Action a where a.performedDate = :date and " + LISTED + " order by a.id asc")
	List<Action> findListedByDate(@Param("userId") Long userId, @Param("date") LocalDate date);

	/** 지난 미완료(FOCUS-017). 미완료만이라 경로가 삭제된 행동은 LISTED 규칙으로 이미 빠진다. */
	@Query("select a from Action a where a.performedDate < :today and a.completed = false and " + LISTED
			+ " order by a.performedDate asc, a.id asc")
	List<Action> findPastIncomplete(@Param("userId") Long userId, @Param("today") LocalDate today);

	/**
	 * 리포트 집계 대상(FN-REPORT-01): 수행일이 [from, to]이고 완료한 GOAL 행동. 사용자가 지운 완료 행동과 경로가 삭제된
	 * 목표의 완료 행동도 포함해야 하므로(결정 7, ERD §4.4) deleted_at·PATH_VISIBLE 조건을 걸지 않는다.
	 * MAINTENANCE(weekly_goal_id가 null)는 제외한다. 표시 순서는 수행일, id 오름차순이다.
	 */
	@Query("select a from Action a where a.userId = :userId and a.weeklyGoalId is not null and a.completed = true "
			+ "and a.performedDate >= :from and a.performedDate <= :to order by a.performedDate asc, a.id asc")
	List<Action> findCompletedGoalActionsBetween(@Param("userId") Long userId, @Param("from") LocalDate from,
			@Param("to") LocalDate to);

	/** 주간 목표 상세·직전 주 요약용. 호출자가 주간 목표의 가시성을 이미 확인했다. 삭제된 행동은 빠진다. */
	@Query("select a from Action a where a.weeklyGoalId in :weeklyIds and a.deletedAt is null "
			+ "order by a.performedDate asc, a.id asc")
	List<Action> findActiveByWeeklyGoals(@Param("weeklyIds") Collection<Long> weeklyIds);

	/** 체크형 진행률의 분모: 미삭제 행동 + 삭제된 완료 행동(ERD 8번 규칙). */
	@Query("select count(a) from Action a where a.weeklyGoalId = :weeklyId and (a.deletedAt is null or a.completed = true)")
	long countDenominator(@Param("weeklyId") Long weeklyId);

	/** 체크형 진행률의 분자: 완료 행동(삭제된 것 포함). */
	@Query("select count(a) from Action a where a.weeklyGoalId = :weeklyId and a.completed = true")
	long countCompleted(@Param("weeklyId") Long weeklyId);

	/**
	 * 주간 목표 여러 개의 (id, 완료 수, 분모)를 한 쿼리로 센다. 규칙은 위 단건 쿼리 두 개와 같다.
	 * 행동이 하나도 없는 주간 목표는 행이 나오지 않으므로 호출자가 (0,0)으로 채운다.
	 */
	@Query("select a.weeklyGoalId, sum(case when a.completed = true then 1 else 0 end), "
			+ "sum(case when a.deletedAt is null or a.completed = true then 1 else 0 end) "
			+ "from Action a where a.weeklyGoalId in :weeklyIds group by a.weeklyGoalId")
	List<Object[]> countByWeeklyGoals(@Param("weeklyIds") Collection<Long> weeklyIds);

	/**
	 * 완료 상태 저장. 행동 행 잠금을 쥔 호출자만 부른다. version을 올리지 않고 영속성 컨텍스트도 비우지 않는다
	 * (호출자가 응답을 만들 때 flush·clear 뒤 다시 읽는다).
	 */
	@Modifying(flushAutomatically = true)
	@Query("update Action a set a.completed = :completed, a.completedAt = :completedAt where a.id = :id")
	int updateCompletion(@Param("id") Long id, @Param("completed") boolean completed,
			@Param("completedAt") LocalDateTime completedAt);

	/** 행동을 삭제 시각만 기록해 숨긴다(소프트 삭제). 완료된 행동은 진행률 분모에 남아야 하기 때문이다. 반환: 갱신된 행 수. */
	@Modifying(flushAutomatically = true)
	@Query("update Action a set a.deletedAt = :deletedAt where a.id = :id")
	int softDelete(@Param("id") Long id, @Param("deletedAt") LocalDateTime deletedAt);

	/**
	 * 삭제 확인 창의 "함께 숨겨질 미완료 행동" 수. 지금 보이는 경로(삭제되지 않은 하위 목표)의 미삭제 미완료 행동만 센다.
	 * 따로 삭제된 하위 목표의 행동은 이미 숨겨져 있으므로 세지 않는다. 호출자가 대상 목표의 가시성을 확인했다.
	 */
	@Query("select count(a) from Action a where a.completed = false and a.deletedAt is null and a.weeklyGoalId = :weeklyId")
	long countIncompleteUnderWeekly(@Param("weeklyId") Long weeklyId);

	/** 월간 목표 삭제 확인 창용: 보이는 주간 목표 아래의 미삭제 미완료 행동 수를 센다. */
	@Query("select count(a) from Action a where a.completed = false and a.deletedAt is null and a.weeklyGoalId in "
			+ "(select w.id from WeeklyGoal w where w.monthlyGoalId = :monthlyId and w.deletedAt is null)")
	long countIncompleteUnderMonthly(@Param("monthlyId") Long monthlyId);

	/** 연간 목표 삭제 확인 창용: 보이는 월간·주간 목표 아래의 미삭제 미완료 행동 수를 센다. */
	@Query("select count(a) from Action a where a.completed = false and a.deletedAt is null and a.weeklyGoalId in "
			+ "(select w.id from WeeklyGoal w, MonthlyGoal m where m.id = w.monthlyGoalId and m.yearlyGoalId = :yearlyId "
			+ "and m.deletedAt is null and w.deletedAt is null)")
	long countIncompleteUnderYearly(@Param("yearlyId") Long yearlyId);
}
