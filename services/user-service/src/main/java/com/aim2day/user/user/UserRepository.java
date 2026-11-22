package com.aim2day.user.user;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * users 테이블 접근 인터페이스(Spring Data JPA가 구현체를 자동 생성한다). 가입·로그인·재설정·내 정보 조회가 공유한다.
 * 이메일은 서비스에서 정규화(공백 제거·소문자)한 값으로 조회해야 UNIQUE 인덱스와 일치한다.
 */
public interface UserRepository extends JpaRepository<User, Long> {

	/** 정규화된 이메일로 사용자를 찾는다. 없으면 빈 Optional(로그인·재설정 요청에서 사용). */
	Optional<User> findByEmail(String email);

	/**
	 * 비밀번호 해시만 DB에서 새로 읽는다. 엔티티가 아니라 스칼라 값을 조회하므로 영속성 컨텍스트(1차 캐시)에 있는
	 * 옛 엔티티를 돌려주지 않고 항상 쿼리가 나간다. 로그인의 "재설정과 겹침" 재확인용이다.
	 */
	@Query("select u.passwordHash from User u where u.id = :id")
	Optional<String> findPasswordHashById(@Param("id") Long id);

	/** 이메일로 이미 가입된 계정이 있는지 확인한다(인증번호 요청·가입 선조회용). 엔티티를 읽지 않아 가볍다. */
	boolean existsByEmail(String email);
}
