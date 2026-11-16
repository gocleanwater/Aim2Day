package com.aim2day.user;

import java.util.TimeZone;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class UserServiceApplication {

	public static void main(String[] args) {
		// 일시는 UTC로 저장·직렬화한다(API 명세 §2.3). 호스트 시간대에 따라 결과가 달라지지 않도록
		// 컨텍스트가 만들어지기 전에 JVM 기본 시간대를 UTC로 고정한다(JDBC, 로그, Date 변환에 영향).
		TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
		SpringApplication.run(UserServiceApplication.class, args);
	}

}
