// 비밀번호 재설정 (#/reset-password#token=...). POST /api/auth/password-reset
// 메일 링크(/reset-password.html#token=...)는 reset-redirect.js가 이 해시로 옮겨 준다.
import { api } from "../api.js";
import { clearUser } from "../auth.js";
import { navigate } from "../navigation.js";
import { attachPasswordChecklist } from "../passwordPolicy.js";
import { clearFormErrors, handleApiError, setBusy, setFieldError, toast } from "../ui.js";

export function render(container, route) {
  // 토큰은 메모리에만 들고 주소창에서는 지운다. 새로고침·뒤로가기 기록·화면 공유에 토큰이 남지 않게 하기 위해서다.
  // (서버는 제출 시점에 토큰을 소비하므로, 새로고침해서 토큰을 잃으면 새 링크를 요청해야 한다.)
  const token = route.fragment.get("token");
  if (token) history.replaceState(null, "", "#/reset-password");

  if (!token) {
    container.innerHTML = `
      <section class="card auth-card">
        <h1>비밀번호 재설정</h1>
        <p class="form-error" role="alert" data-testid="reset-no-token">재설정 링크가 올바르지 않아요. 메일의 링크를 다시 열거나 새 링크를 요청해 주세요.</p>
        <div class="auth-links"><a href="#/forgot-password">재설정 링크 다시 받기</a><a href="#/login">로그인</a></div>
      </section>`;
    return;
  }

  container.innerHTML = `
    <section class="card auth-card">
      <h1>비밀번호 재설정</h1>
      <form id="reset-form" novalidate>
        <p class="form-error" data-form-error role="alert" hidden></p>
        <div class="field">
          <label for="reset-password">새 비밀번호</label>
          <input id="reset-password" name="newPassword" type="password" autocomplete="new-password" required data-testid="reset-password" />
          <div id="reset-checklist"></div>
          <p class="field-error" data-error-for="newPassword"></p>
        </div>
        <div class="field">
          <label for="reset-confirm">새 비밀번호 확인</label>
          <input id="reset-confirm" name="newPasswordConfirm" type="password" autocomplete="new-password" required data-testid="reset-confirm" />
          <p class="field-error" data-error-for="newPasswordConfirm"></p>
        </div>
        <button id="reset-submit" type="submit" class="btn btn-block" disabled data-testid="reset-submit">비밀번호 바꾸기</button>
      </form>
    </section>`;

  const form = container.querySelector("#reset-form");
  const submit = container.querySelector("#reset-submit");
  // 다섯 조건을 모두 만족하기 전에는 제출 버튼을 잠근다(화면 흐름 문서).
  attachPasswordChecklist(form.newPassword, container.querySelector("#reset-checklist"), (ok) => {
    submit.disabled = !ok;
  });

  form.addEventListener("submit", async (event) => {
    event.preventDefault();
    clearFormErrors(form);
    const newPassword = form.newPassword.value;
    const newPasswordConfirm = form.newPasswordConfirm.value;
    if (newPassword !== newPasswordConfirm) {
      setFieldError(form, "newPasswordConfirm", "비밀번호가 일치하지 않습니다.");
      form.newPasswordConfirm.focus();
      return;
    }
    setBusy(submit, true, "변경 중…");
    try {
      await api.post("/api/auth/password-reset", { body: { token, newPassword, newPasswordConfirm } });
      // 서버가 이 사용자의 모든 세션을 지우므로 이 브라우저의 로그인 표시도 비운다.
      clearUser();
      toast("비밀번호를 바꿨어요. 새 비밀번호로 로그인해 주세요.");
      // 응답을 기다리는 사이 사용자가 다른 화면으로 갔다면 그 화면을 로그인으로 빼앗지 않는다(토스트로 결과만 알린다).
      if (route.isActive()) navigate("#/login");
    } catch (error) {
      // 이미 떠난 화면이면 #view(다른 화면이 쓰는 중)를 건드리지 않는다. 오류는 토스트로만 알린다.
      if (!route.isActive()) {
        toast(error.message, { error: true });
        return;
      }
      setBusy(submit, false);
      // 만료·사용된 토큰은 비밀번호를 다시 입력해도 소용없으니 새 링크 요청을 안내한다.
      if (error.code === "AUTH_INVALID_TOKEN") {
        container.innerHTML = `
          <section class="card auth-card">
            <h1>비밀번호 재설정</h1>
            <p class="form-error" role="alert" data-testid="reset-invalid-token"></p>
            <div class="auth-links"><a href="#/forgot-password">재설정 링크 다시 받기</a><a href="#/login">로그인</a></div>
          </section>`;
        container.querySelector(".form-error").textContent = error.message;
        return;
      }
      handleApiError(error, { form });
    }
  });

  form.newPassword.focus();
}
