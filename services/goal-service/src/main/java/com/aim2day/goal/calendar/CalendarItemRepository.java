package com.aim2day.goal.calendar;

import jakarta.persistence.LockModeType;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 모든 조회는 user_id 조건을 함께 건다. 남의 항목은 없는 항목과 구분되지 않아야 하므로(API 명세 §2.5)
 * 소유 확인을 조회 조건에 넣는다. 달력 항목은 소프트 삭제가 없다(hard-delete).
 */
public interface CalendarItemRepository extends JpaRepository<CalendarItem, Long> {

	/** 행 잠금(SELECT ... FOR UPDATE). 수정·완료를 한 줄로 세우고, 잠근 뒤 상태를 다시 읽는 데 쓴다. */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select c from CalendarItem c where c.id = :id and c.userId = :userId")
	Optional<CalendarItem> findOwnedForUpdate(@Param("id") Long id, @Param("userId") Long userId);

	/** [from, to]와 하루라도 겹치는 항목(start <= to, end >= from). 목표 필터는 호출자가 가시성과 함께 적용한다. */
	@Query("select c from CalendarItem c where c.userId = :userId and c.startDate <= :to and c.endDate >= :from "
			+ "order by c.startDate asc, c.id asc")
	List<CalendarItem> findOverlapping(@Param("userId") Long userId, @Param("from") LocalDate from,
			@Param("to") LocalDate to);

	/** 대시보드 해야 할 일: 기한과 관계없이 미완료 TASK 전체. 마감일, id 순. */
	@Query("select c from CalendarItem c where c.userId = :userId "
			+ "and c.itemType = com.aim2day.goal.calendar.CalendarItemType.TASK "
			+ "and c.completed = false order by c.startDate asc, c.id asc")
	List<CalendarItem> findOpenTasks(@Param("userId") Long userId);

	/** 대시보드 일정: startDate <= today+7 이고 endDate >= today인 EVENT(완료 여부 무관). 시작일, id 순. */
	@Query("select c from CalendarItem c where c.userId = :userId "
			+ "and c.itemType = com.aim2day.goal.calendar.CalendarItemType.EVENT "
			+ "and c.startDate <= :until and c.endDate >= :today order by c.startDate asc, c.id asc")
	List<CalendarItem> findUpcomingEvents(@Param("userId") Long userId, @Param("today") LocalDate today,
			@Param("until") LocalDate until);

	/** 대시보드 지난 일정: since <= endDate < today인 EVENT. 종료일, id 순. */
	@Query("select c from CalendarItem c where c.userId = :userId "
			+ "and c.itemType = com.aim2day.goal.calendar.CalendarItemType.EVENT "
			+ "and c.endDate >= :since and c.endDate < :today order by c.endDate asc, c.id asc")
	List<CalendarItem> findPastEvents(@Param("userId") Long userId, @Param("since") LocalDate since,
			@Param("today") LocalDate today);

	/** 완료 상태 저장. 항목 행 잠금을 쥔 호출자만 부른다. version을 올리지 않는다. */
	@Modifying(flushAutomatically = true)
	@Query("update CalendarItem c set c.completed = :completed, c.completedAt = :completedAt where c.id = :id")
	int updateCompletion(@Param("id") Long id, @Param("completed") boolean completed,
			@Param("completedAt") LocalDateTime completedAt);

	/** 즉시 영구 삭제. 소유자 조건을 함께 걸어 남의 항목은 0행이 된다. */
	@Modifying
	@Query("delete from CalendarItem c where c.id = :id and c.userId = :userId")
	int deleteOwned(@Param("id") Long id, @Param("userId") Long userId);
}
