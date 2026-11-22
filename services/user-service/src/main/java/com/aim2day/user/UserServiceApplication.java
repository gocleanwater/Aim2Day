package com.aim2day.user;

import java.util.TimeZone;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * user-service(인증·계정 서비스) 애플리케이션 진입점. 컴포넌트 스캔은 이 패키지(com.aim2day.user) 아래를 대상으로 한다.
 * 가입·로그인·로그아웃·비밀번호 재설정과 내 정보 조회를 제공하며, 세션은 Redis에 저장해 goal-service와 공유한다.
 */
@SpringBootApplication
public class UserServiceApplication {

	/** JVM 시간대를 UTC로 고정한 뒤 Spring 컨텍스트를 기동한다. */
	public static void main(String[] args) {
		// 일시는 UTC로 저장·직렬화한다(API 명세 §2.3). 호스트 시간대에 따라 결과가 달라지지 않도록
		// 컨텍스트가 만들어지기 전에 JVM 기본 시간대를 UTC로 고정한다(JDBC, 로그, Date 변환에 영향).
		TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
		SpringApplication.run(UserServiceApplication.class, args);
	}

}
