package com.aim2day.goal.settings;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 사용자 설정(user_settings) 테이블 접근 인터페이스.
 * 사용자당 한 행만 있으므로(user_id UNIQUE) 모든 조회는 세션의 userId로 건다. 경로에 설정 id가 없어 남의 행에 닿을 수 없다.
 */
public interface UserSettingRepository extends JpaRepository<UserSetting, Long> {

	/**
	 * userId가 소유한 설정 행을 찾는다.
	 * 반환: 행이 있으면 그 설정, 아직 한 번도 저장·조회한 적이 없으면 빈 Optional(호출자가 기본값으로 만든다).
	 */
	Optional<UserSetting> findByUserId(Long userId);
}
