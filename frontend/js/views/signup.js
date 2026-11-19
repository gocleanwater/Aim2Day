// 회원가입 (#/signup). 한 화면에서 단계가 열리는 방식(화면 흐름 문서 "회원가입").
//   1) 이메일 입력 → [인증번호 받기]      POST /api/auth/email-codes
//   2) 인증번호 입력 → [확인]              POST /api/auth/email-codes/verify  → 가입권(signupToken) 보관
//   3) 사용자명·비밀번호 → [가입하기]      POST /api/auth/signup              → 성공하면 로그인 화면으로
// 앞 단계를 마치기 전에는 다음 단계 입력칸이 비활성이다.
import { api } from "../api.js";
import { navigate } from "../navigation.js";
import { attachPasswordChecklist } from "../passwordPolicy.js";
import {
  clearFieldError,
  clearFormErrors,
  handleApiError,
  setBusy,
  setFieldError,
  startCountdown,
  toast,
} from "../ui.js";

const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
const CODE_PATTERN = /^\d{6}$/;

export function render(container) {
  container.innerHTML = `
    <section class="card auth-card">
      <h1>회원가입</h1>
      <form id="signup-form" novalidate>
        <p class="form-error" data-form-error role="alert" hidden></p>

        <div class="field">
          <label for="signup-email">1. 이메일</label>
          <div class="field-row">
            <input id="signup-email" name="email" type="email" autocomplete="email" required data-testid="signup-email" />
            <button id="send-code" type="button" class="btn btn-secondary" disabled data-testid="send-code">인증번호 받기</button>
          </div>
          <p class="field-hint" id="email-hint">가입과 로그인에 사용합니다. 이 주소로 6자리 인증번호를 보냅니다.</p>
          <p class="step-done" id="email-done" hidden>인증 완료</p>
          <p class="field-error" data-error-for="email"></p>
        </div>

        <div class="field">
          <label for="signup-code">2. 인증번호</label>
          <div class="field-row">
            <input id="signup-code" name="code" type="text" inputmode="numeric" maxlength="6" autocomplete="one-time-code" disabled data-testid="signup-code" />
            <button id="verify-code" type="button" class="btn btn-secondary" disabled data-testid="verify-code">확인</button>
          </div>
          <p class="field-hint" id="code-hint">인증번호 6자리를 입력해 주세요. 10분 안에 입력해야 합니다.</p>
          <p class="field-error" data-error-for="code" role="alert"></p>
        </div>

        <div class="field">
          <label for="signup-username">3. 사용자명</label>
          <input id="signup-username" name="username" type="text" maxlength="50" autocomplete="nickname" disabled data-testid="signup-username" />
          <p class="field-hint">화면에 표시되는 이름입니다. 다른 사람과 같아도 됩니다.</p>
          <p class="field-error" data-error-for="username"></p>
        </div>

        <div class="field">
          <label for="signup-password">비밀번호</label>
          <input id="signup-password" name="password" type="password" maxlength="64" autocomplete="new-password" disabled data-testid="signup-password" />
          <p class="field-hint">8~64자, 대문자·소문자·숫자·기호 각 1자 이상</p>
          <div id="signup-checklist"></div>
          <p class="field-error" data-error-for="password"></p>
        </div>

        <div class="field">
          <label for="signup-confirm">비밀번호 확인</label>
          <input id="signup-confirm" name="passwordConfirm" type="password" maxlength="64" autocomplete="new-password" disabled data-testid="signup-confirm" />
          <p class="field-error" data-error-for="passwordConfirm"></p>
        </div>

        <button id="signup-submit" type="submit" class="btn btn-block" disabled data-testid="signup-submit">가입하기</button>
      </form>
      <div class="auth-links"><span>이미 계정이 있나요? <a href="#/login">로그인</a></span></div>
    </section>`;

  const form = container.querySelector("#signup-form");
  const emailInput = form.email;
  const sendButton = container.querySelector("#send-code");
  const codeInput = form.code;
  const verifyButton = container.querySelector("#verify-code");
  const usernameInput = form.username;
  const passwordInput = form.password;
  const confirmInput = form.passwordConfirm;
  const submitButton = container.querySelector("#signup-submit");
  const emailHint = container.querySelector("#email-hint");
  const emailDone = container.querySelector("#email-done");
  const codeHint = container.querySelector("#code-hint");

  // ---- 화면 상태 ----
  // sentEmail: 인증번호를 보낸 이메일(서버는 소문자·공백 제거로 비교하므로 같은 규칙으로 맞춘다)
  // signupToken: 인증 성공 때 받은 가입권. 화면 메모리에만 두고 저장소에는 넣지 않는다.
  let sentEmail = null;
  let signupToken = null;
  let passwordOk = false;
  let stopCountdown = null;
  let alive = true; // 화면을 떠난 뒤 늦게 온 응답이 토스트·이동을 일으키지 않게 한다
  // 인증번호 요청(발송·확인)마다 올리는 번호. 응답이 왔을 때 "내가 보낸 요청이 아직 현재 상태에 유효한가"를 판단한다.
  // 이메일 A로 확인 요청을 보낸 사이 B로 바꾸면, A의 가입권을 B의 인증으로 착각할 수 있으므로
  // (1) 요청 중에는 이메일 입력칸을 잠그고 (2) 응답 때 번호와 이메일이 그대로인지 한 번 더 확인한다.
  let requestSeq = 0;

  const normalizeEmail = () => emailInput.value.trim().toLowerCase();

  // 발송·확인 요청이 공유하는 "요청 중" 잠금. 값은 잠금을 잡은 요청의 번호(lockSeq)이고, 없으면 null이다.
  // 확인 요청 중에 인증번호 칸을 고치면 [확인]이 다시 열려 두 번째 요청이 나가고, 두 번째가 requestSeq를 올려
  // 첫 요청의 (서버가 이미 번호를 소모한) 성공 응답을 버리게 되어 사용자가 막힌다. 그래서 요청 중에는
  // 버튼을 모두 막고 입력칸을 읽기 전용으로 만든다. requestSeq와 따로 두는 이유는, 응답 처리 중
  // resetCodeStep()이 requestSeq를 올려도 잠금 해제는 자기 요청 번호로 정확히 되어야 하기 때문이다.
  let lockSeq = 0;
  let pendingLock = null;

  function acquireLock() {
    const mine = ++lockSeq;
    pendingLock = mine;
    sendButton.disabled = true;
    verifyButton.disabled = true;
    codeInput.readOnly = true;
    emailInput.readOnly = true;
    return mine;
  }

  /** 자기가 잡은 잠금만 푼다. 더 새 요청이 잠금을 잡고 있다면(번호 불일치) 건드리지 않는다. */
  function releaseLock(mine) {
    if (pendingLock !== mine) return;
    pendingLock = null;
    codeInput.readOnly = false;
    emailInput.readOnly = signupToken !== null; // 인증이 끝났다면 이메일은 계속 고정
    refreshSendButton();
    refreshVerifyButton();
  }

  /** 이메일이 형식에 맞고(가입권 받기 전) 카운트다운 중이 아니면 [인증번호 받기]를 연다. */
  function refreshSendButton() {
    if (signupToken || stopCountdown) return;
    sendButton.disabled = pendingLock !== null || !EMAIL_PATTERN.test(emailInput.value.trim());
  }

  function refreshVerifyButton() {
    verifyButton.disabled = pendingLock !== null || signupToken !== null || !sentEmail || !CODE_PATTERN.test(codeInput.value);
  }

  function refreshSubmitButton() {
    submitButton.disabled = !(signupToken && passwordOk);
  }

  function stopTimer() {
    stopCountdown?.();
    stopCountdown = null;
  }

  /** 60초 재요청 제한을 버튼에 표시한다. 서버(429)가 알려 주면 그 값을 쓴다. */
  function startResendCountdown(seconds) {
    stopTimer();
    stopCountdown = startCountdown(
      sendButton,
      seconds,
      (left) => `다시 받기 (${left}초 뒤)`,
      () => {
        stopCountdown = null;
        sendButton.textContent = "다시 받기";
        refreshSendButton();
      },
    );
  }

  /** 인증번호 단계를 처음 상태로 되돌린다(이메일을 바꿨거나 번호가 만료됐을 때). */
  function resetCodeStep({ keepCountdown = false } = {}) {
    requestSeq += 1; // 진행 중이던 요청의 응답은 더 이상 적용하지 않는다
    sentEmail = null;
    codeInput.value = "";
    codeInput.disabled = true;
    clearFieldError(form, "code");
    codeHint.hidden = false;
    if (!keepCountdown) {
      stopTimer();
      sendButton.textContent = "인증번호 받기";
    }
    refreshSendButton();
    refreshVerifyButton();
  }

  /** 가입권이 만료·사용된 경우 등: 처음 단계부터 다시 인증하도록 모든 단계를 되돌린다. */
  function resetAll() {
    signupToken = null;
    requestSeq += 1;
    emailInput.readOnly = false;
    emailDone.hidden = true;
    emailHint.hidden = false;
    for (const input of [usernameInput, passwordInput, confirmInput]) {
      input.disabled = true;
      input.value = "";
    }
    passwordInput.dispatchEvent(new Event("input")); // 체크리스트 초기화
    resetCodeStep();
    refreshSubmitButton();
  }

  // ---- 이벤트 ----
  emailInput.addEventListener("input", () => {
    clearFieldError(form, "email");
    // 인증번호를 보낸 뒤 이메일을 고치면 이전 번호는 다른 주소의 것이므로 단계를 되돌린다.
    if (sentEmail && normalizeEmail() !== sentEmail) resetCodeStep();
    refreshSendButton();
  });

  sendButton.addEventListener("click", async () => {
    if (pendingLock !== null) return; // 다른 요청이 진행 중이면 무시
    clearFieldError(form, "email");
    const email = normalizeEmail();
    const seq = ++requestSeq;
    const lock = acquireLock(); // 중복 클릭 방지, 이메일·인증번호 입력 잠금
    const stale = () => !alive || seq !== requestSeq || normalizeEmail() !== email;
    try {
      await api.post("/api/auth/email-codes", { body: { email } });
      if (!alive) return;
      if (stale()) return void refreshSendButton(); // 그 사이 상태가 바뀌었다면 이 응답은 버린다
      sentEmail = email;
      codeInput.disabled = false;
      codeInput.value = "";
      clearFieldError(form, "code");
      codeInput.focus();
      toast("인증번호를 보냈어요. 메일함을 확인해 주세요.");
      startResendCountdown(60);
      refreshVerifyButton();
    } catch (error) {
      if (stale()) return void (alive && refreshSendButton());
      if (error.code === "AUTH_EMAIL_EXISTS") {
        setFieldError(form, "email", error.message);
        refreshSendButton();
      } else if (error.code === "TOO_MANY_REQUESTS") {
        // 이전 인증번호는 아직 유효하다. 남은 시간만큼 기다리게 하고, 입력칸은 그대로 둔다.
        setFieldError(form, "email", error.message);
        const wait = error.details?.retryAfterSeconds;
        startResendCountdown(Number.isFinite(wait) && wait > 0 ? wait : 60);
      } else if (error.code === "AUTH_MAIL_SEND_FAILED") {
        // 서버가 제한을 지웠으므로 바로 다시 요청할 수 있다(화면 흐름 문서).
        setFieldError(form, "email", error.message);
        refreshSendButton();
      } else {
        handleApiError(error, { form });
        refreshSendButton();
      }
    } finally {
      releaseLock(lock);
    }
  });

  codeInput.addEventListener("input", () => {
    // 숫자 외 문자는 바로 걸러 6자리 숫자 문자열만 보낸다(앞의 0 유지를 위해 문자열 그대로).
    codeInput.value = codeInput.value.replace(/\D/g, "");
    clearFieldError(form, "code");
    refreshVerifyButton();
  });

  verifyButton.addEventListener("click", async () => {
    if (pendingLock !== null) return; // 다른 요청이 진행 중이면 두 번째 요청을 보내지 않는다
    clearFieldError(form, "code");
    const email = sentEmail;
    const seq = ++requestSeq;
    const lock = acquireLock();
    const stale = () => !alive || seq !== requestSeq || sentEmail !== email || normalizeEmail() !== email;
    setBusy(verifyButton, true, "확인 중…");
    try {
      const result = await api.post("/api/auth/email-codes/verify", {
        body: { email, code: codeInput.value },
      });
      if (stale()) {
        // 가입권은 요청한 이메일의 것이므로, 현재 입력된 이메일과 어긋나면 쓰지 않는다.
        if (alive) {
          setBusy(verifyButton, false);
          refreshVerifyButton();
        }
        return;
      }
      signupToken = result.signupToken;
      // 인증된 이메일은 고정하고 다음 단계를 연다.
      stopTimer();
      emailInput.readOnly = true;
      emailHint.hidden = true;
      emailDone.hidden = false;
      sendButton.disabled = true;
      sendButton.textContent = "인증 완료";
      codeInput.disabled = true;
      codeHint.hidden = true;
      for (const input of [usernameInput, passwordInput, confirmInput]) input.disabled = false;
      usernameInput.focus();
      setBusy(verifyButton, false);
      verifyButton.disabled = true;
      refreshSubmitButton();
    } catch (error) {
      if (!alive) return;
      setBusy(verifyButton, false);
      if (stale()) return void refreshVerifyButton();
      if (error.code === "AUTH_INVALID_CODE") {
        const left = error.details?.remainingAttempts;
        // 화면 흐름 문서의 문구. 남은 횟수가 없으면 서버 message를 쓴다.
        setFieldError(
          form,
          "code",
          Number.isFinite(left) ? `인증번호가 맞지 않습니다. 남은 시도 ${left}회` : error.message,
        );
        codeInput.select();
      } else if (error.code === "AUTH_CODE_EXPIRED") {
        // 5회 실패이거나 10분 경과: 새 번호를 받아야 한다. 재요청 간격이 남아 있을 수 있어 카운트다운은 유지한다.
        resetCodeStep({ keepCountdown: true });
        setFieldError(form, "code", error.message);
      } else {
        handleApiError(error, { form });
      }
      refreshVerifyButton();
    } finally {
      releaseLock(lock);
    }
  });

  attachPasswordChecklist(passwordInput, container.querySelector("#signup-checklist"), (ok) => {
    passwordOk = ok;
    refreshSubmitButton();
  });
  passwordInput.addEventListener("input", () => clearFieldError(form, "password"));
  confirmInput.addEventListener("input", () => clearFieldError(form, "passwordConfirm"));
  usernameInput.addEventListener("input", () => clearFieldError(form, "username"));

  form.addEventListener("submit", async (event) => {
    event.preventDefault();
    if (!signupToken) return;
    clearFormErrors(form);
    const username = usernameInput.value.trim();
    const password = passwordInput.value;
    const passwordConfirm = confirmInput.value;

    // 서버가 같은 규칙으로 다시 검사한다. 여기서는 헛요청을 줄이려고 간단한 것만 먼저 확인한다.
    if (!username) {
      setFieldError(form, "username", "사용자명을 입력해 주세요.");
      usernameInput.focus();
      return;
    }
    if (password !== passwordConfirm) {
      setFieldError(form, "passwordConfirm", "비밀번호가 일치하지 않습니다.");
      confirmInput.focus();
      return;
    }

    setBusy(submitButton, true, "가입 중…");
    try {
      await api.post("/api/auth/signup", { body: { signupToken, username, password, passwordConfirm } });
      if (!alive) return;
      // 가입 직후 자동 로그인하지 않는다. 토스트는 화면이 바뀌어도 유지되므로 로그인 화면에서 읽힌다.
      toast("가입이 완료됐어요. 로그인해 주세요.");
      navigate("#/login");
    } catch (error) {
      if (!alive) return;
      setBusy(submitButton, false);
      refreshSubmitButton();
      if (error.code === "AUTH_INVALID_TOKEN") {
        // 가입권이 만료됐거나 이미 사용됨: 인증번호부터 다시 받아야 한다.
        resetAll();
        setFieldError(form, "email", `${error.message} 처음 단계부터 다시 인증해 주세요.`);
      } else if (error.code === "AUTH_EMAIL_EXISTS") {
        resetAll();
        setFieldError(form, "email", error.message);
      } else {
        handleApiError(error, { form });
      }
    }
  });

  emailInput.focus();
  return () => {
    alive = false;
    stopTimer();
  };
}
