// 비밀번호 재설정 요청 (#/forgot-password). POST /api/auth/password-reset-requests
// 서버는 가입 여부와 상관없이 200을 주므로(API 결정 15) 화면도 "있다면 보냈다"는 중립 문구만 보여 준다.
import { api } from "../api.js";
import { clearFormErrors, handleApiError, setBusy, setFieldError, startCountdown, toast } from "../ui.js";

export function render(container, route) {
  container.innerHTML = `
    <section class="card auth-card">
      <h1>비밀번호 재설정</h1>
      <p class="lead">가입한 이메일을 입력하면 비밀번호를 다시 정하는 링크를 보내 드려요.</p>
      <form id="forgot-form" novalidate>
        <p class="form-error" data-form-error role="alert" hidden></p>
        <p class="form-success" id="forgot-done" role="status" hidden></p>
        <div class="field">
          <label for="forgot-email">이메일</label>
          <input id="forgot-email" name="email" type="email" autocomplete="email" required data-testid="forgot-email" />
          <p class="field-error" data-error-for="email"></p>
        </div>
        <button id="forgot-submit" type="submit" class="btn btn-block" data-testid="forgot-submit">재설정 링크 받기</button>
      </form>
      <div class="auth-links"><a href="#/login">로그인으로 돌아가기</a></div>
    </section>`;

  const form = container.querySelector("#forgot-form");
  const submit = container.querySelector("#forgot-submit");
  const done = container.querySelector("#forgot-done");
  let stopCountdown = null;

  // 같은 이메일 재요청은 서버가 60초에 한 번만 허용한다. 버튼에도 같은 간격을 표시해 헛요청을 줄인다.
  const lockButton = (seconds) => {
    stopCountdown?.();
    stopCountdown = startCountdown(
      submit,
      seconds,
      (left) => `다시 보내기 (${left}초 뒤)`,
      () => {
        submit.disabled = false;
        submit.textContent = "재설정 링크 받기";
      },
    );
  };

  form.addEventListener("submit", async (event) => {
    event.preventDefault();
    clearFormErrors(form);
    done.hidden = true;
    const email = form.email.value.trim();
    if (!email) {
      setFieldError(form, "email", "이메일을 입력해 주세요.");
      form.email.focus();
      return;
    }
    setBusy(submit, true, "보내는 중…");
    try {
      await api.post("/api/auth/password-reset-requests", { body: { email } });
      if (!route.isActive()) return; // 화면을 떠났다면 카운트다운·안내를 다시 붙이지 않는다
      done.textContent = "입력한 이메일로 가입된 계정이 있다면 재설정 링크를 보냈어요. 메일함을 확인해 주세요. 링크는 30분 동안 유효해요.";
      done.hidden = false;
      setBusy(submit, false);
      lockButton(60);
    } catch (error) {
      if (!route.isActive()) return; // 떠난 화면에는 오류도 카운트다운도 붙이지 않는다
      setBusy(submit, false);
      if (error.code === "TOO_MANY_REQUESTS") {
        // 서버 message를 그대로 보여 주고, 남은 초가 있으면 버튼을 그만큼 잠근다.
        toast(error.message, { error: true });
        const wait = error.details?.retryAfterSeconds;
        if (Number.isFinite(wait) && wait > 0) lockButton(wait);
        return;
      }
      handleApiError(error, { form });
    }
  });

  container.querySelector("#forgot-email").focus();
  return () => stopCountdown?.();
}
