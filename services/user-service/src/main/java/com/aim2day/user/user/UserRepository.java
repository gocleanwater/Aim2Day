package com.aim2day.user.user;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, Long> {

	Optional<User> findByEmail(String email);

	/**
	 * 비밀번호 해시만 DB에서 새로 읽는다. 엔티티가 아니라 스칼라 값을 조회하므로 영속성 컨텍스트(1차 캐시)에 있는
	 * 옛 엔티티를 돌려주지 않고 항상 쿼리가 나간다. 로그인의 "재설정과 겹침" 재확인용이다.
	 */
	@Query("select u.passwordHash from User u where u.id = :id")
	Optional<String> findPasswordHashById(@Param("id") Long id);

	boolean existsByEmail(String email);
}
