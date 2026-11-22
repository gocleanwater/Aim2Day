package com.aim2day.goal.onboarding;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 모든 조회는 user_id로 건다. 경로에 임시 저장 id가 없어 다른 사용자의 행에 닿을 수 없다. */
public interface OnboardingDraftRepository extends JpaRepository<OnboardingDraft, Long> {

	/** userId의 온보딩 임시 저장본을 찾는다. 반환: 저장본, 없으면 빈 Optional. */
	Optional<OnboardingDraft> findByUserId(Long userId);

	/** userId의 온보딩 임시 저장본이 있는지 확인한다. 반환: 있으면 true. */
	boolean existsByUserId(Long userId);

	/** 삭제. users 행 잠금을 쥔 호출자만 부른다. 없어도 0행이 지워질 뿐 오류가 아니다. */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("delete from OnboardingDraft d where d.userId = :userId")
	int deleteByUserId(@Param("userId") Long userId);
}
