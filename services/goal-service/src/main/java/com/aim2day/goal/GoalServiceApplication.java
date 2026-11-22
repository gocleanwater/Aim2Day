package com.aim2day.goal;

import java.util.TimeZone;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * goal-service 진입점. 목표·행동·달력·리포트 API를 제공하는 Spring Boot 애플리케이션이다.
 * 같은 이미지로 컨테이너 3개가 뜨고 Nginx가 요청을 나눠 주므로, 사용자 상태는 인스턴스 메모리가 아니라 MySQL과 Redis 세션에만 둔다.
 */
@SpringBootApplication
public class GoalServiceApplication {

	// JVM 기본 시간대를 UTC로 고정한다(API 명세 §2.3: 일시는 UTC로 저장·응답).
	// static 블록이라 main뿐 아니라 테스트가 이 클래스를 읽을 때도 적용되고, DB 연결·Jackson 초기화보다 먼저 실행된다.
	static {
		TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
	}

	/** 애플리케이션을 시작한다. 내장 Tomcat이 뜨고 Flyway 마이그레이션이 먼저 적용된다. */
	public static void main(String[] args) {
		SpringApplication.run(GoalServiceApplication.class, args);
	}

}
