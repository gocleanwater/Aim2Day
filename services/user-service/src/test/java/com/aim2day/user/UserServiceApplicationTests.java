package com.aim2day.user;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import com.aim2day.user.support.TestSessionConfig;

@SpringBootTest
@Import(TestSessionConfig.class)
class UserServiceApplicationTests {

	@Test
	void contextLoads() {
	}

}
