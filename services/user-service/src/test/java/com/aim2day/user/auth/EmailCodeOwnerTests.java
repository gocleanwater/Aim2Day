package com.aim2day.user.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.aim2day.user.auth.token.AuthTokenStore;
import com.aim2day.user.auth.token.AuthTokenStore.CodeStatus;
import com.aim2day.user.common.response.BusinessException;
import com.aim2day.user.common.response.ErrorCode;
import com.aim2day.user.mail.AuthMailSender;
import com.aim2day.user.mail.MailDeliveryException;
import com.aim2day.user.support.AuthTestConfig;
import com.aim2day.user.support.AuthTestConfig.InMemoryTokenStore;
import com.aim2day.user.user.UserRepository;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** 메일 전송 실패 정리가 "자기 발급분"만 지우는지 검증한다. 번호 생성기를 고정해 새 요청이 같은 번호를 받는 최악의 경우를 만든다. */
class EmailCodeOwnerTests {

	private static final String EMAIL = "user@example.com";
	private static final String FIXED = "123456";

	@Test
	void lateFailureOfOlderRequestKeepsNewerRequestsCodeAndThrottle() throws Exception {
		InMemoryTokenStore store = new InMemoryTokenStore();
		CountDownLatch firstSending = new CountDownLatch(1);
		CountDownLatch releaseFirst = new CountDownLatch(1);
		AtomicInteger calls = new AtomicInteger();
		// 첫 요청의 메일 전송은 오래 걸리다 실패하고, 두 번째 요청의 전송은 즉시 성공한다.
		AuthMailSender sender = AuthTestConfig.signupSender((to, code) -> {
			if (calls.incrementAndGet() == 1) {
				firstSending.countDown();
				try {
					releaseFirst.await(10, TimeUnit.SECONDS);
				}
				catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
				throw new MailDeliveryException("slow failure", new RuntimeException());
			}
		});
		EmailCodeService service = new EmailCodeService(mock(UserRepository.class), store, sender, () -> FIXED);

		ExecutorService pool = Executors.newSingleThreadExecutor();
		Future<?> first = pool.submit(() -> service.requestCode(EMAIL));
		assertThat(firstSending.await(10, TimeUnit.SECONDS)).isTrue();

		// 첫 요청이 전송 중인 동안 60초가 지났다고 보고(제한 만료) 두 번째 요청이 같은 번호로 발급된다.
		store.expireThrottle(AuthTokenStore.PURPOSE_SIGNUP, EMAIL);
		service.requestCode(EMAIL);

		// 이제 첫 요청이 늦게 실패한다. 번호가 같아도 소유자 ID가 달라 두 번째 요청의 번호·제한은 남아야 한다.
		releaseFirst.countDown();
		assertThatThrownBy(() -> first.get(10, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
				.hasCauseInstanceOf(BusinessException.class);
		pool.shutdown();

		assertThat(store.hasCode(EMAIL)).isTrue();
		assertThat(store.isThrottled(AuthTokenStore.PURPOSE_SIGNUP, EMAIL)).isTrue();
		assertThat(store.verifyCode(EMAIL, FIXED).status()).isEqualTo(CodeStatus.OK);
	}

	@Test
	void failureOfOwnRequestClearsOwnCodeAndThrottle() {
		InMemoryTokenStore store = new InMemoryTokenStore();
		AuthMailSender failing = AuthTestConfig.signupSender((to, code) -> {
			throw new MailDeliveryException("fail", new RuntimeException());
		});
		EmailCodeService service = new EmailCodeService(mock(UserRepository.class), store, failing, () -> FIXED);

		assertThatThrownBy(() -> service.requestCode(EMAIL)).isInstanceOf(BusinessException.class);

		assertThat(store.hasCode(EMAIL)).isFalse();
		assertThat(store.isThrottled(AuthTokenStore.PURPOSE_SIGNUP, EMAIL)).isFalse();
	}

	/**
	 * 제한을 얻은 뒤 저장 직전에 멈춘 요청 A가, 그 사이 제한 만료 후 발급을 끝낸 요청 B의 번호를 덮어쓰지 못해야 한다.
	 */
	@Test
	void staleRequestPausedBeforeSaveDoesNotOverwriteNewerCodeNorSendMail() throws Exception {
		CountDownLatch aAcquired = new CountDownLatch(1);
		CountDownLatch resumeA = new CountDownLatch(1);
		AtomicInteger saves = new AtomicInteger();
		// 첫 저장 시도(A)만 제한 획득 후·저장 전에 멈춘다. 대기는 저장소 락 밖(오버라이드 안)에서 한다.
		InMemoryTokenStore store = new InMemoryTokenStore() {
			@Override
			public boolean saveCode(String email, String code, String ownerId) {
				if (saves.incrementAndGet() == 1) {
					aAcquired.countDown();
					try {
						resumeA.await(10, TimeUnit.SECONDS);
					}
					catch (InterruptedException e) {
						Thread.currentThread().interrupt();
					}
				}
				return super.saveCode(email, code, ownerId);
			}
		};
		List<String> sentCodes = new CopyOnWriteArrayList<>();
		AuthMailSender sender = AuthTestConfig.signupSender((to, code) -> sentCodes.add(code));
		AtomicInteger seq = new AtomicInteger();
		// A는 111111, B는 222222를 받는다.
		EmailCodeService service = new EmailCodeService(mock(UserRepository.class), store, sender,
				() -> seq.incrementAndGet() == 1 ? "111111" : "222222");

		ExecutorService pool = Executors.newSingleThreadExecutor();
		Future<?> a = pool.submit(() -> service.requestCode(EMAIL));
		assertThat(aAcquired.await(10, TimeUnit.SECONDS)).isTrue();

		// 60초가 지나 A의 제한이 만료된 것으로 보고, B가 제한을 얻어 발급·발송을 마친다.
		store.expireThrottle(AuthTokenStore.PURPOSE_SIGNUP, EMAIL);
		service.requestCode(EMAIL);
		assertThat(sentCodes).containsExactly("222222");

		// A를 재개한다. 저장은 거절되고 메일 없이 429로 끝나야 한다.
		resumeA.countDown();
		assertThatThrownBy(() -> a.get(10, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
				.cause().isInstanceOfSatisfying(BusinessException.class,
						e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.TOO_MANY_REQUESTS));
		pool.shutdown();

		assertThat(sentCodes).containsExactly("222222");
		assertThat(store.isThrottled(AuthTokenStore.PURPOSE_SIGNUP, EMAIL)).isTrue();
		assertThat(store.verifyCode(EMAIL, "222222").status()).isEqualTo(CodeStatus.OK);
	}
}
