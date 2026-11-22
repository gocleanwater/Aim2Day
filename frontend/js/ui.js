// 화면 공통 도우미: 토스트, 확인 모달, 폼 오류 표시, 로딩 상태, 오류 처리 규칙.
// 이 모듈은 api.js를 import하지 않는다. 오류는 error.code로만 판단(덕 타이핑)해서 의존을 단방향으로 유지한다.

// ---------- 토스트 ----------
let toastTimer = null;

/**
 * 화면 아래에 잠깐 알림을 띄운다. #toast는 aria-live="polite" 영역이라 스크린리더도 읽는다.
 * @param {string} message
 * @param {{error?: boolean, duration?: number}} [options]
 */
export function toast(message, options = {}) {
  const el = document.getElementById("toast");
  if (!el) return;
  clearTimeout(toastTimer);
  el.textContent = message;
  el.classList.toggle("error", Boolean(options.error));
  el.hidden = false;
  toastTimer = setTimeout(() => {
    el.hidden = true;
  }, options.duration ?? 4000);
}

// ---------- 확인 모달 ----------
/**
 * 확인 모달. 네이티브 <dialog>를 쓰면 포커스 가두기·Esc 닫기를 브라우저가 처리해 준다.
 * @returns {Promise<boolean>} 확인이면 true, 취소·Esc면 false
 */
export function confirmDialog({ title, message = "", confirmText = "확인", cancelText = "취소" }) {
  return new Promise((resolve) => {
    const dialog = document.createElement("dialog");
    dialog.className = "modal";
    dialog.setAttribute("aria-labelledby", "modal-title");
    dialog.innerHTML = `
      <h2 id="modal-title"></h2>
      <p></p>
      <div class="modal-actions">
        <button type="button" class="btn btn-outline" data-action="cancel"></button>
        <button type="button" class="btn" data-action="confirm"></button>
      </div>`;
    dialog.querySelector("h2").textContent = title;
    dialog.querySelector("p").textContent = message;
    dialog.querySelector('[data-action="cancel"]').textContent = cancelText;
    dialog.querySelector('[data-action="confirm"]').textContent = confirmText;

    let result = false;
    dialog.addEventListener("click", (event) => {
      const action = event.target.closest?.("[data-action]")?.dataset.action;
      if (action === "confirm") result = true;
      if (action) dialog.close();
    });
    // Esc로 닫혀도 close 이벤트가 오므로 resolve는 여기서 한 번만 한다.
    dialog.addEventListener("close", () => {
      dialog.remove();
      resolve(result);
    });
    document.body.append(dialog);
    dialog.showModal();
  });
}

/**
 * 앱이 body에 붙인 모달(<dialog class="modal">)을 모두 닫고 지운다. 라우터가 화면을 바꿀 때 부른다.
 * 모달은 #view 밖(body)에 붙어 있어서 화면 정리만으로는 남는다. 저장 중 401로 로그인 화면에 가도
 * 모달이 로그인 폼을 덮고 있으면 사용할 수 없으므로, 이동할 때마다 열린 모달을 정리한다.
 * close()는 각 모달의 close 이벤트(확인 모달은 false로 resolve)를 일으키므로 대기 중인 Promise도 풀린다.
 */
export function closeAllModals() {
  document.querySelectorAll("dialog.modal").forEach((dialog) => {
    if (dialog.open) dialog.close();
    dialog.remove();
  });
}

// ---------- 폼 오류 ----------
// 규칙: 입력칸마다 `.field` 안에 `<p class="field-error" data-error-for="필드명">`을 두고,
// 서버 details.fieldErrors의 field 이름과 입력칸의 name을 같게 맞춘다.

// VALIDATION_FAILED의 reason별 안내 문구(명세 §2.8에 reason 목록만 있고 문구는 없으므로 화면에서 정한다).
const REASON_MESSAGES = {
  REQUIRED: "필수 입력입니다.",
  INVALID_FORMAT: "형식이 올바르지 않습니다.",
  TOO_LONG: "입력이 너무 깁니다.",
  OUT_OF_RANGE: "허용 범위를 벗어났습니다.",
  PASSWORD_POLICY:
    "비밀번호는 8~64자이며 영문 대문자, 영문 소문자, 숫자, 기호를 각각 1자 이상 포함해야 합니다. 공백·한글·이모지는 쓸 수 없습니다.",
  PASSWORD_MISMATCH: "비밀번호가 일치하지 않습니다.",
};

/** 폼 안에서 name이 field인 입력칸을 찾는다. 서버 details.fieldErrors의 field 이름과 name을 맞춰 두었기 때문에 가능하다. */
function controlOf(form, field) {
  return form.querySelector(`[name="${field}"]`);
}

/**
 * 한 입력칸 아래 오류 문구를 채우고 접근성 속성(aria-invalid, aria-describedby)을 붙인다.
 * @returns {boolean} 오류 자리(data-error-for)가 있어 표시했으면 true. 없으면 false라서 호출자가 다른 방식으로 알려야 한다.
 */
export function setFieldError(form, field, message) {
  const errorEl = form.querySelector(`[data-error-for="${field}"]`);
  const control = controlOf(form, field);
  if (!errorEl) return false;
  errorEl.textContent = message;
  errorEl.id = errorEl.id || `error-${field}`;
  if (control) {
    control.setAttribute("aria-invalid", "true");
    control.setAttribute("aria-describedby", errorEl.id);
  }
  return true;
}

/** 한 입력칸의 오류 문구와 접근성 속성을 지운다. 사용자가 다시 입력하기 시작할 때 쓴다. */
export function clearFieldError(form, field) {
  const errorEl = form.querySelector(`[data-error-for="${field}"]`);
  const control = controlOf(form, field);
  if (errorEl) errorEl.textContent = "";
  if (control) {
    control.removeAttribute("aria-invalid");
    control.removeAttribute("aria-describedby");
  }
}

/** 폼 전체의 필드 오류와 폼 수준 오류 배너를 지운다. 제출할 때마다 먼저 호출한다. */
export function clearFormErrors(form) {
  form.querySelectorAll("[data-error-for]").forEach((el) => clearFieldError(form, el.dataset.errorFor));
  const banner = form.querySelector("[data-form-error]");
  if (banner) {
    banner.textContent = "";
    banner.hidden = true;
  }
}

/** 특정 입력칸에 속하지 않는 오류(예: 로그인 실패)를 폼 위 배너로 보여 준다. */
export function setFormError(form, message) {
  const banner = form.querySelector("[data-form-error]");
  if (!banner) {
    toast(message, { error: true });
    return;
  }
  banner.textContent = message;
  banner.hidden = false;
}

/**
 * 400 VALIDATION_FAILED의 details.fieldErrors를 해당 입력칸 아래에 표시한다.
 * @returns {number} 입력칸에 표시한 개수(0이면 호출자가 폼 수준 오류로 대신 보여 준다)
 */
export function showFieldErrors(form, fieldErrors, policyMessage) {
  let shown = 0;
  let firstControl = null;
  for (const { field, reason } of fieldErrors || []) {
    const message =
      reason === "PASSWORD_POLICY" && policyMessage ? policyMessage : REASON_MESSAGES[reason] || "입력값을 확인해 주세요.";
    if (setFieldError(form, field, message)) {
      shown += 1;
      firstControl = firstControl || controlOf(form, field);
    }
  }
  if (firstControl) firstControl.focus();
  return shown;
}

// ---------- 로딩 ----------
/** 제출 중 버튼을 잠가 중복 요청을 막고 문구를 바꾼다. */
export function setBusy(button, busy, busyText = "처리 중…") {
  if (!button) return;
  if (busy) {
    button.dataset.label = button.textContent;
    button.textContent = busyText;
    button.disabled = true;
    button.setAttribute("aria-busy", "true");
  } else {
    if (button.dataset.label !== undefined) button.textContent = button.dataset.label;
    delete button.dataset.label;
    button.disabled = false;
    button.removeAttribute("aria-busy");
  }
}

/** 화면 데이터를 불러오는 동안 보여 줄 표시. */
export function renderLoading(container, text = "불러오는 중…") {
  container.innerHTML = `<p class="loading" role="status"></p>`;
  container.querySelector(".loading").textContent = text;
}

/**
 * 버튼에 카운트다운을 건다(재요청 간격 60초 등). 끝나면 버튼을 다시 활성화한다.
 * @param {(secondsLeft:number)=>string} labelFor 남은 초로 버튼 문구를 만든다
 * @returns {() => void} 중단 함수(화면을 떠날 때 부른다)
 */
export function startCountdown(button, seconds, labelFor, onDone) {
  let left = seconds;
  button.disabled = true;
  button.textContent = labelFor(left);
  const timer = setInterval(() => {
    left -= 1;
    if (left <= 0) {
      clearInterval(timer);
      onDone();
      return;
    }
    button.textContent = labelFor(left);
  }, 1000);
  return () => clearInterval(timer);
}

// ---------- 오류 처리 규칙 ----------
/**
 * API 오류를 사용자에게 보이는 방식으로 바꾸는 공통 규칙. 분기는 error.code로만 한다.
 *  - UNAUTHENTICATED: api.js가 이미 로그인 화면으로 보냈으므로 여기서는 아무것도 하지 않는다.
 *  - VALIDATION_FAILED: details.fieldErrors를 입력칸 아래에 표시한다.
 *  - VERSION_CONFLICT: "다른 곳에서 먼저 수정" 안내 후 onVersionConflict로 최신 값을 다시 불러오게 한다.
 *  - 그 밖: 서버 message를 그대로 보여 준다(폼이 있으면 폼 배너, 없으면 토스트).
 * 화면별 특수 코드(예: AUTH_INVALID_CODE)는 호출 전에 화면이 먼저 처리하고 남은 것만 여기로 넘긴다.
 */
export function handleApiError(error, { form = null, onVersionConflict = null } = {}) {
  if (!error || typeof error.code !== "string") {
    toast("알 수 없는 오류가 발생했어요.", { error: true });
    console.error(error);
    return;
  }
  switch (error.code) {
    case "UNAUTHENTICATED":
      return;
    case "VALIDATION_FAILED": {
      const shown = form ? showFieldErrors(form, error.details?.fieldErrors, error.message) : 0;
      if (shown === 0) {
        if (form) setFormError(form, error.message);
        else toast(error.message, { error: true });
      }
      return;
    }
    case "VERSION_CONFLICT":
      toast("다른 곳에서 먼저 수정됐어요. 최신 내용을 다시 불러왔어요.", { error: true });
      if (onVersionConflict) onVersionConflict(error);
      return;
    default:
      if (form) setFormError(form, error.message);
      else toast(error.message, { error: true });
  }
}

// ---------- DOM 생성 도우미 ----------
/**
 * 작은 DOM 생성 도우미. 사용자 입력이 섞인 값은 text로만 들어가므로 HTML로 해석되지 않는다.
 * 특수 속성: text(글자), class, testid(data-testid), onxxx(이벤트). false/null/undefined 값은 생략한다.
 */
export function el(tag, attrs = {}, ...children) {
  const node = document.createElement(tag);
  for (const [key, value] of Object.entries(attrs)) {
    if (value === false || value === null || value === undefined) continue;
    if (key === "text") node.textContent = value;
    else if (key === "class") node.className = value;
    else if (key === "testid") node.dataset.testid = value;
    else if (key.startsWith("on")) node.addEventListener(key.slice(2), value);
    else node.setAttribute(key, value === true ? "" : value);
  }
  node.append(...children.flat().filter((child) => child !== null && child !== undefined));
  return node;
}

/**
 * label + 입력칸 + 오류 자리를 묶은 폼 필드. 오류 자리의 data-error-for와 입력칸의 name이 같아야 폼 오류 표시가 붙는다.
 * control에는 id가 있어야 label이 연결된다.
 */
export function formField(labelText, control, name, hint) {
  return el(
    "div",
    { class: "field" },
    el("label", { for: control.id, text: labelText }),
    control,
    hint ? el("p", { class: "field-hint", text: hint }) : null,
    el("p", { class: "field-error", "data-error-for": name }),
  );
}

// ---------- 표시 형식 ----------
/** 2026-11-12 → 2026.11.12 (명세 §2.3, 화면 표시용) */
/** 서버의 YYYY-MM-DD를 표시용으로만 바꾼다. 날짜 계산에는 쓰지 않고(계산은 dateUtil.js), 문자열이 아니면 빈 문자열이다. */
export function formatDate(isoDate) {
  return typeof isoDate === "string" ? isoDate.replaceAll("-", ".") : "";
}
