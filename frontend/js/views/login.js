// 로그인 화면 (#/login). POST /api/auth/login → GET /api/goals/onboarding/status로 갈 곳을 정한다.
import { api } from "../api.js";
import { routeAfterLogin, setUser } from "../auth.js";
import { clearFormErrors, handleApiError, setBusy, setFieldError } from "../ui.js";

export function render(container, route) {
  container.innerHTML = `
    <section class="card auth-card">
      <h1>로그인</h1>
      <form id="login-form" novalidate>
        <p class="form-error" data-form-error role="alert" hidden></p>
        <div class="field">
          <label for="login-email">이메일</label>
          <input id="login-email" name="email" type="email" autocomplete="username" required data-testid="login-email" />
          <p class="field-error" data-error-for="email"></p>
        </div>
        <div class="field">
          <label for="login-password">비밀번호</label>
          <input id="login-password" name="password" type="password" autocomplete="current-password" required data-testid="login-password" />
          <p class="field-error" data-error-for="password"></p>
        </div>
        <button id="login-submit" type="submit" class="btn btn-block" data-testid="login-submit">로그인</button>
      </form>
      <div class="auth-links">
        <a href="#/signup" data-testid="link-signup">회원가입</a>
        <a href="#/forgot-password" data-testid="link-forgot">비밀번호 재설정</a>
      </div>
    </section>`;

  const form = container.querySelector("#login-form");
  const submit = container.querySelector("#login-submit");

  form.addEventListener("submit", async (event) => {
    event.preventDefault();
    clearFormErrors(form);
    // 서버 이메일 규칙은 앞뒤 공백 제거·소문자 변환이지만 그 처리는 서버가 하므로 값은 그대로(공백만 제거) 보낸다.
    const email = form.email.value.trim();
    const password = form.password.value;

    // 비어 있으면 요청 없이 바로 안내한다(서버도 REQUIRED로 막는다).
    let invalid = false;
    if (!email) {
      setFieldError(form, "email", "이메일을 입력해 주세요.");
      invalid = true;
    }
    if (!password) {
      setFieldError(form, "password", "비밀번호를 입력해 주세요.");
      invalid = true;
    }
    if (invalid) {
      form.querySelector("[aria-invalid]").focus();
      return;
    }

    setBusy(submit, true, "로그인 중…");
    try {
      const user = await api.post("/api/auth/login", { body: { email, password } });
      setUser(user); // 서버 세션은 이미 만들어졌으므로 로그인 표시는 화면과 무관하게 반영한다(헤더에 사용자명 표시)
      // 응답을 기다리는 사이 사용자가 다른 화면으로 갔다면 그 화면을 원래 목적지로 덮어 이동시키지 않는다.
      if (route.isActive()) await routeAfterLogin();
    } catch (error) {
      if (!route.isActive()) return; // 떠난 화면에 오류를 그리지 않는다
      // AUTH_INVALID_CREDENTIALS 등은 서버 message를 폼 위에 그대로 보여 준다.
      handleApiError(error, { form });
    } finally {
      setBusy(submit, false);
    }
  });

  container.querySelector("#login-email").focus();
}
