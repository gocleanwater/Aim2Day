package com.aim2day.goal;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
@Import(com.aim2day.goal.support.TestSessionConfig.class)
class GoalServiceApplicationTests {

	@Test
	void contextLoads() {
	}

}
