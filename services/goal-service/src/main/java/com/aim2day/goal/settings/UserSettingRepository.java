package com.aim2day.goal.settings;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserSettingRepository extends JpaRepository<UserSetting, Long> {

	Optional<UserSetting> findByUserId(Long userId);
}
