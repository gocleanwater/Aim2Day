package com.aim2day.goal.quadrant;

import com.aim2day.goal.actions.ActionRepository;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 사분면 좌표(quadrant_placements) 테이블 접근 인터페이스.
 * 좌표 행은 (행동, 분류 날짜) 단위이며, 소유권은 행동을 소유자 조건으로 먼저 조회해 확인한다.
 */
public interface QuadrantPlacementRepository extends JpaRepository<QuadrantPlacement, Long> {

	/**
	 * 행동 하나의 특정 분류 날짜 좌표 행을 찾는다.
	 * 반환: 있으면 그 행, 아직 좌표를 둔 적이 없으면 빈 Optional(미분류 상태).
	 */
	Optional<QuadrantPlacement> findByActionIdAndClassificationDate(Long actionId, LocalDate classificationDate);

	/**
	 * 행동 묶음의 좌표 행. 행동의 좌표는 classification_date가 그 행동의 performed_date인 행이다
	 * (수행일을 옮기면 이전 날짜의 행은 남지만 새 날짜에서는 행이 없어 미분류로 시작한다). 한 쿼리로 읽는다.
	 */
	@Query("select p from QuadrantPlacement p, Action a where a.id in :actionIds "
			+ "and p.actionId = a.id and p.classificationDate = a.performedDate")
	List<QuadrantPlacement> findForActions(@Param("actionIds") Collection<Long> actionIds);

	/**
	 * 확정 대상: 오늘 수행일이고 오늘 목록에 보이는 행동(삭제·경로 삭제 숨김 제외)의 오늘자 행.
	 * 다른 날짜의 행, 숨김 행동의 행, 수행일을 옮겨 오늘이 아니게 된 행동의 행은 건드리지 않는다.
	 */
	@Query("select p from QuadrantPlacement p, Action a where p.actionId = a.id and p.classificationDate = :today "
			+ "and a.performedDate = :today and " + ActionRepository.LISTED + " order by p.id asc")
	List<QuadrantPlacement> findConfirmable(@Param("userId") Long userId, @Param("today") LocalDate today);
}
