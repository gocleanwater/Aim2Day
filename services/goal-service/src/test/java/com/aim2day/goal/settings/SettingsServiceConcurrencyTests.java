package com.aim2day.goal.settings;

import static org.assertj.core.api.Assertions.assertThat;

import com.aim2day.goal.support.TestSessionConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
@Import(TestSessionConfig.class)
class SettingsServiceConcurrencyTests {

	@Autowired
	SettingsService service;
	@Autowired
	UserSettingRepository repository;
	@Autowired
	JdbcTemplate jdbc;

	@BeforeEach
	void reset() {
		jdbc.update("DELETE FROM user_settings");
		jdbc.update("MERGE INTO users (id) KEY (id) VALUES (?)", 7L);
	}

	/** 두 인스턴스가 같은 사용자의 첫 GET을 동시에 받는 상황. 유니크 위반을 맞은 쪽도 성공해야 하고 행은 1개여야 한다. */
	@Test
	void concurrentFirstReadCreatesOneRow() throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(2);
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch go = new CountDownLatch(1);
		List<Future<UserSetting>> results = new ArrayList<>();
		try {
			for (int i = 0; i < 2; i++) {
				results.add(pool.submit(() -> {
					ready.countDown();
					go.await();
					return service.getOrCreate(7L);
				}));
			}
			ready.await();
			go.countDown();
			for (Future<UserSetting> f : results) {
				assertThat(f.get(30, TimeUnit.SECONDS).getUserId()).isEqualTo(7L);
			}
		} finally {
			pool.shutdownNow();
		}
		assertThat(repository.count()).isEqualTo(1);
	}
}
