package com.aim2day.goal;

import java.util.TimeZone;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class GoalServiceApplication {

	// JVM 기본 시간대를 UTC로 고정한다(API 명세 §2.3: 일시는 UTC로 저장·응답).
	// static 블록이라 main뿐 아니라 테스트가 이 클래스를 읽을 때도 적용되고, DB 연결·Jackson 초기화보다 먼저 실행된다.
	static {
		TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
	}

	public static void main(String[] args) {
		SpringApplication.run(GoalServiceApplication.class, args);
	}

}
