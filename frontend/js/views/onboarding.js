// 온보딩 (#/onboarding): 올해 목표 → 이번 달 목표 → 이번 주 목표 → 오늘 행동, 네 질문을 순서대로 묻는다.
//
// 동작 요약
//  - 진입: status·draft·설정·기존 목표를 병렬로 읽는다. 임시 저장(draft)이 있으면 그 단계·내용으로 이어서 한다.
//  - 단계 이동(다음·이전·나중에 하기)마다 PUT /api/goals/onboarding/draft로 저장한다.
//    version은 처음에는 생략하고 이후에는 응답의 version을 그대로 돌려 보낸다. 409면 서버의 최신 draft를 받아 이어간다.
//  - [계획 완료]는 POST /api/goals/onboarding/complete 한 번으로 목표와 행동을 함께 만든다(서버가 한 트랜잭션으로 처리).
//    실패하면 서버의 임시 저장은 그대로 남으므로 사용자는 고쳐서 다시 누르면 된다.
//  - 입력 중 값은 메모리의 draft 객체에 두고, 입력이 멈춘 뒤 0.8초(디바운스)에 자동 저장한다(PLAN-002: 작성 중 나가도 입력 유지).
//    저장 요청은 하나씩만 순서대로 보낸다(같은 version으로 두 번 보내면 스스로 409를 만든다).
//    화면을 떠날 때(앱 안 이동, 탭 숨김·닫기)도 바뀐 내용이 있으면 한 번 더 저장한다.
//  - 저장 충돌(409): 사용자가 방금 입력한 내용을 화면에 그대로 두고 자동 저장을 멈춘 뒤,
//    "내 입력으로 저장" / "저장된 내용 불러오기" 중 고르게 한다. 고르기 전에는 단계 이동·완료를 막는다.
//  - 저장하지 못한 입력은 localStorage에도 남겨(UNSAVED_KEY) 탭을 닫거나 새로고침해도 잃지 않게 한다.
//    저장에 성공하면 지우고, 다음 진입 때 서버 내용과 다르면 같은 충돌 안내로 고르게 한다.
import { api } from "../api.js";
import { clearOnboardingDeferred, deferOnboarding, getUser } from "../auth.js";
import { navigate } from "../navigation.js";
import {
  MAX_DRAFT_BYTES,
  actionMonthlyKey,
  buildCompleteBody,
  draftBytes,
  emptyDraft,
  hasContent,
  hasParentsForStep,
  isBlank,
  monthlyOptions,
  nextTempId,
  normalizeDraft,
  pruneBlank,
  refKey,
  resolveWeekly,
  sanitizeRefs,
  setRef,
  weeklyOptionsFor,
  yearlyOptions,
} from "../onboardingModel.js";
import {
  clearFormErrors,
  confirmDialog,
  formatDate,
  handleApiError,
  renderLoading,
  setBusy,
  setFieldError,
  setFormError,
  showFieldErrors,
  toast,
} from "../ui.js";

const STEP_HINTS = [
  "이미 세워둔 올해 목표가 있다면 그대로 적어도 좋아요.",
  "올해 목표와 연결해 이번 달의 결과를 정해보세요.",
  "이번 달 목표에서 한 걸음 더 구체적으로 정해보세요.",
  "이번 주 목표에 연결되는 행동을 적어보세요.",
];

// 서버 오류 경로(yearlyGoals[0].title)가 속한 단계
const AUTOSAVE_MS = 800;
// fetch keepalive 요청 본문은 브라우저가 64KB로 제한하므로 여유를 두고 넘으면 keepalive 없이 보낸다.
const KEEPALIVE_MAX_BYTES = 60000;

// 저장하지 못한 입력의 사본. 사용자마다 키를 나눈다: `${UNSAVED_KEY}:${owner}`.
// 값: {owner, rev, step, draftData, baseVersion, savedAt}. baseVersion은 이 입력이 기반으로 한 서버 draft의 version(없으면 null).
// rev는 사본을 쓸 때마다 새로 만드는 식별자다. 늦게 도착한 저장 응답이 더 새로운 사본을 지우지 않게 비교하는 데 쓴다.
// 이름 없는 옛 키(UNSAVED_KEY 자체)는 사용자 구분이 없어 더 이상 쓰지 않고, 로그아웃 때 지운다.
export const UNSAVED_KEY = "aim2day.onboardingUnsaved";
const unsavedKeyOf = (owner) => `${UNSAVED_KEY}:${owner}`;

/** 현재 로그인 사용자의 식별자. 알 수 없으면 "". 화면이 시작될 때 한 번만 읽어 화면 수명 동안 쓴다. */
export function currentOwner() {
  const user = getUser();
  return user ? String(user.email ?? user.userId ?? "") : "";
}

/**
 * 해당 사용자의 사본을 지운다. owner가 비어 있으면 아무것도 하지 않는다.
 * localStorage를 쓸 수 없는 환경(사생활 보호 모드 등)에서도 화면이 깨지지 않도록 오류를 삼킨다.
 */
export function clearUnsavedDraft(owner = currentOwner()) {
  if (!owner) return;
  try {
    localStorage.removeItem(unsavedKeyOf(owner));
  } catch {
    /* 저장소를 못 쓰면 지울 것도 없다 */
  }
}

/** 로그아웃용: 해당 사용자의 사본과 옛 공용 키를 함께 지운다. */
export function clearAllUnsavedDrafts(owner = currentOwner()) {
  clearUnsavedDraft(owner);
  try {
    localStorage.removeItem(UNSAVED_KEY);
  } catch {
    /* 저장소를 못 쓰면 지울 것도 없다 */
  }
}

function readUnsavedDraft(owner) {
  if (!owner) return null;
  try {
    const parsed = JSON.parse(localStorage.getItem(unsavedKeyOf(owner)) ?? "null");
    // 키가 사용자별이지만 값의 owner도 한 번 더 확인한다.
    if (parsed && typeof parsed === "object" && parsed.draftData && typeof parsed.draftData === "object" && parsed.owner === owner) return parsed;
  } catch {
    /* 깨진 값은 없는 것으로 본다 */
  }
  return null;
}

/** 저장된 사본의 rev가 expectedRev와 같을 때만 지운다. 그 사이 다른 화면·탭이 새로 쓴 사본은 남긴다. */
function clearUnsavedDraftIfRev(owner, expectedRev) {
  if (!owner || !expectedRev) return;
  if (readUnsavedDraft(owner)?.rev === expectedRev) clearUnsavedDraft(owner);
}

const LIST_STEP = { yearlyGoals: 1, monthlyGoals: 2, weeklyGoals: 3, actions: 4 };

// ---------- DOM 도우미 ----------
function el(tag, attrs = {}, ...children) {
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

const idOf = (name) => `onb-${name.replace(/\W+/g, "-")}`;

/** label + 입력칸 + 오류 자리. 오류 자리의 data-error-for와 입력칸의 name이 같아야 ui.js가 오류를 붙인다. */
function field(labelText, control, name, hint) {
  return el(
    "div",
    { class: "field" },
    el("label", { for: control.id, text: labelText }),
    control,
    hint ? el("p", { class: "field-hint", text: hint }) : null,
    el("p", { class: "field-error", "data-error-for": name }),
  );
}

function textInput({ name, value, maxlength, testid, placeholder, type = "text", onInput, attrs = {} }) {
  const input = el("input", { id: idOf(name), name, type, maxlength, placeholder, testid, ...attrs });
  input.value = value ?? "";
  input.addEventListener("input", () => onInput(input.value));
  return input;
}

function selectInput({ name, options, value, testid, placeholder = "선택", onChange }) {
  const select = el("select", { id: idOf(name), name, testid });
  select.append(el("option", { value: "", text: placeholder }));
  for (const option of options) select.append(el("option", { value: option.key, text: option.label }));
  select.value = options.some((o) => o.key === value) ? value : "";
  select.addEventListener("change", () => onChange(select.value));
  return select;
}

/** 라디오 묶음. choices: [{value,label,testid}] */
function radioGroup({ name, legend, choices, value, onChange }) {
  const group = el("div", { class: "radio-grid", role: "radiogroup" });
  for (const choice of choices) {
    const input = el("input", { type: "radio", name, value: choice.value, testid: choice.testid });
    input.checked = choice.value === value;
    input.addEventListener("change", () => input.checked && onChange(choice.value));
    group.append(el("label", {}, input, document.createTextNode(choice.label)));
  }
  return el("fieldset", {}, el("legend", { text: legend }), group, el("p", { class: "field-error", "data-error-for": name }));
}

// ---------- 화면 ----------
export function render(container, route) {
  renderLoading(container);

  // 화면이 시작될 때 한 번만 읽은 사용자 식별자. 자동 저장 중 401로 clearUser()가 불려도 이 화면의 정리 코드(persistUnsaved)가
  // 같은 사용자의 키에 사본을 남길 수 있게 하고, 식별자를 모르면("") 사본을 쓰지 않는다.
  const owner = currentOwner();
  const clearLocal = () => clearUnsavedDraft(owner);
  // 이 화면이 마지막으로 쓴(또는 불러온) 사본의 rev. 저장 요청이 끝났을 때 "내가 쓴 사본이 아직 그대로인지" 확인하는 데 쓴다.
  let myRev = null;
  let revCounter = 0;
  const newRev = () => `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}-${++revCounter}`;

  // 서버에서 읽은 기준 정보(기존 목표, 오늘·이번 주·이번 달)
  let ctx = null;
  let settings = null;
  let status = null;
  // 편집 중인 내용과 임시 저장 상태
  let draft = emptyDraft();
  let version = null; // null이면 서버에 임시 저장이 없다고 보고 version을 생략한다
  let step = 1;
  let busy = false;
  // 자동 저장 상태
  let alive = true; // 화면을 떠나면 false: 이후 늦게 온 응답이 #view를 다시 그리지 못하게 한다
  let finished = false; // 계획 완료·처음부터 다시 직후: 서버 draft가 지워졌으므로 다시 만들지 않게 자동 저장을 끈다
  // 서버에서 임시 저장이 사라진 것이 확인되면(다른 탭에서 온보딩을 완료했거나 지움) true.
  // 이 상태에서 version 없이 저장하면 오래된 탭이 완료된 임시 저장을 되살리게 되므로(API §4.2),
  // saveChain에 이미 줄 선 저장까지 포함해 모든 저장을 보내기 직전(doSave)에 막는다.
  // 사용자가 "다시 작성하기"를 직접 누를 때만 해제된다.
  let stopped = false;
  // 저장 충돌(409) 또는 이전 방문의 미저장 입력과 서버 내용이 달라 사용자가 고르는 중이면 true.
  // true인 동안에는 자동·이탈 저장, 단계 이동, 완료를 모두 막아 서버 내용을 사용자 모르게 덮어쓰지 않는다.
  let conflict = false;
  let resolvingConflict = false; // 충돌 해결 버튼 연타 방지
  // 409 뒤 서버 최신 내용을 확인하지 못한(네트워크 오류) 상태. conflict는 계속 true로 두어 완료·다시 시작을 막고,
  // [다시 확인]으로 확인에 성공한 뒤에야 사용자가 선택지를 고를 수 있다.
  let conflictUnconfirmed = false;
  let loggingOut = false; // 명시적 로그아웃 중: 서버 저장과 로컬 사본 쓰기를 모두 막는다
  // 로컬에 남긴 입력이 기반으로 한 서버 version. 충돌 중에는 현재 version(서버 최신)이 아니라 이 값을 로컬 사본에 기록해야
  // 다음 진입 때도 "서버가 그 사이 바뀌었다"는 사실이 유지된다.
  let localBase = null;
  let autosaveTimer = null;
  let saveChain = Promise.resolve(); // 저장 요청을 한 줄로 세운다(동시에 둘 이상 보내지 않음)
  let savedSnapshot = null; // 마지막으로 서버와 일치한 [단계, 내용]. 같으면 보낼 것이 없다
  let saveStatusText = "";
  // 현재 단계 화면의 요소
  let form = null;
  let rowsHost = null;

  const yearLimit = () => ({
    max: status.maxYearlyGoalCount,
    active: status.activeYearlyGoalCount,
    remaining: Math.max(0, status.maxYearlyGoalCount - status.activeYearlyGoalCount),
  });

  // ---------- 불러오기 ----------
  async function load() {
    const [loadedStatus, loadedDraft, loadedSettings, yearly, monthly, weekly] = await Promise.all([
      api.get("/api/goals/onboarding/status"),
      api.get("/api/goals/onboarding/draft"),
      api.get("/api/settings"),
      api.get("/api/goals/yearly"),
      api.get("/api/goals/monthly"),
      api.get("/api/goals/weekly"),
    ]);
    if (!route.isActive()) return;

    // 가정: 온보딩이 필요 없으면(올해 목표가 있고 임시 저장도 없음) 대시보드로 보낸다. status의 needsOnboarding만 보고 판단한다.
    if (!loadedStatus.needsOnboarding) {
      navigate("#/dashboard", { replace: true });
      return;
    }

    status = loadedStatus;
    settings = loadedSettings;
    ctx = {
      existingYearly: yearly.items ?? [],
      existingMonthly: Array.isArray(monthly) ? monthly : (monthly.items ?? []),
      existingWeekly: Array.isArray(weekly) ? weekly : (weekly.items ?? []),
    };
    // 기존 목표 수는 yearly 응답이 가장 최신이다(status와 같은 값이지만 같은 요청 묶음에서 한 번 더 맞춘다).
    status.activeYearlyGoalCount = yearly.activeCount ?? status.activeYearlyGoalCount;
    status.maxYearlyGoalCount = yearly.maxCount ?? status.maxYearlyGoalCount;

    if (loadedDraft) adoptServerDraft(loadedDraft);
    if (!restoreUnsavedInput(loadedDraft) && loadedDraft) {
      toast("이전에 작성하던 내용을 불러왔어요. 이어서 진행해 주세요.");
    }
    renderStep();
  }

  /**
   * 탭을 닫거나 새로고침하기 전에 저장하지 못한 입력(localStorage 사본)이 있으면 처리한다.
   * 서버 draft는 이미 adoptServerDraft로 반영된 상태이며, 이 함수가 true를 돌려주면 사본 입력이 화면에 올라간 것이다.
   *  - 사본이 서버 내용과 같으면(keepalive 저장이 이미 도착함 등) 조용히 지운다.
   *  - 서버 draft가 우리가 기반으로 한 version 그대로면 다른 곳에서 바뀐 것이 없으므로 사본 입력을 그대로 되살리고 자동 저장이 이어서 보낸다.
   *  - 그 밖에는(서버가 그 사이 바뀜·삭제됨) 충돌 안내로 사용자가 고르게 한다. 화면에는 사본 입력을 올려 둔다.
   * @returns {boolean} 사본 입력을 화면에 올렸으면 true
   */
  function restoreUnsavedInput(serverDraft) {
    const local = readUnsavedDraft(owner);
    if (!local) return false;
    myRev = typeof local.rev === "string" ? local.rev : null; // 불러온 사본을 이 화면이 이어받는다
    const localDraft = normalizeDraft(local.draftData);
    const localStep = Math.min(4, Math.max(1, Number(local.step) || 1));
    sanitizeRefs(localDraft, ctx);
    const prunedLocal = structuredClone(localDraft);
    pruneBlank(prunedLocal);
    if (snapshotOf(localStep, prunedLocal) === savedSnapshot) {
      clearLocal();
      return false;
    }
    const serverVersion = serverDraft?.version ?? null;
    const baseVersion = local.baseVersion ?? null;
    draft = localDraft;
    step = localStep;
    if (serverVersion === baseVersion) {
      // 서버는 우리가 마지막으로 본 상태 그대로다. 이 입력은 단지 아직 못 보낸 것이므로 평소 자동 저장으로 이어서 보낸다.
      toast("저장하지 못한 입력을 이어서 불러왔어요.");
      scheduleAutosave();
      return true;
    }
    conflict = true;
    localBase = baseVersion;
    return true;
  }

  // ---------- 로컬 사본 ----------
  /**
   * 지금 입력이 서버에 저장된 내용과 다르면 localStorage에 사본을 남기고, 같으면 지운다.
   * 입력이 바뀔 때마다·화면을 떠날 때 부른다. 저장 요청과 달리 동기 호출이라 탭이 닫히는 순간에도 끝난다.
   */
  function persistUnsaved() {
    // 이미 떠난 화면(늦게 온 저장 응답)이나 로그아웃 중인 화면은 사본을 쓰지 않는다. 새 화면·다른 탭이 쓴 더 새로운 사본을 덮을 수 있다.
    if (finished || !owner || !alive || loggingOut) return; // 사용자를 모르면 누구의 사본인지 알 수 없으므로 쓰지 않는다
    const data = draftForSave();
    if (snapshotOf(step, data) === savedSnapshot) {
      clearUnsavedDraftIfRev(owner, myRev); // 내가 쓴 사본만 지운다(다른 탭이 새로 쓴 사본은 남긴다)
      return;
    }
    if (!hasContent(data) && version === null) return; // 서버에도 없고 입력도 없으면 남길 것이 없다
    // 소유권 확인: 저장소에 다른 rev의 사본이 있으면 이 화면이 쓴 것이 아니므로 덮지 않는다.
    const stored = readUnsavedDraft(owner);
    if (stored && stored.rev !== myRev) return;
    try {
      const rev = newRev();
      localStorage.setItem(
        unsavedKeyOf(owner),
        JSON.stringify({ owner, rev, step, draftData: data, baseVersion: conflict ? localBase : version, savedAt: new Date().toISOString() }),
      );
      myRev = rev;
    } catch {
      /* 용량 초과·차단: 서버 자동 저장이 본 경로이므로 조용히 넘어간다 */
    }
  }

  function adoptServerDraft(serverDraft) {
    draft = normalizeDraft(serverDraft.draftData);
    version = serverDraft.version ?? null;
    step = Math.min(4, Math.max(1, Number(serverDraft.currentStep) || 1));
    // 기존 목표가 그 사이 바뀌었을 수 있으므로 끊긴 참조를 정리한다.
    sanitizeRefs(draft, ctx);
    savedSnapshot = snapshotOf(step, draftForSave());
  }

  // ---------- 임시 저장 ----------
  /** 서버로 보낼 내용: 빈 행을 뺀 복사본. 입력 중인 draft 자체는 건드리지 않아 자동 저장이 입력 행을 없애지 않는다. */
  function draftForSave() {
    const copy = structuredClone(draft);
    pruneBlank(copy);
    return copy;
  }

  // seq(임시 ID 카운터)는 빈 행을 그리기만 해도 올라가므로 비교에서 뺀다. 넣으면 입력이 없어도 "바뀜"으로 보여
  // 새로고침 때 keepalive 저장이 나가고, 새 페이지가 그보다 먼저 읽은 version으로 저장하다 409가 나 입력을 잃는다.
  const snapshotOf = (forStep, data) => JSON.stringify([forStep, { ...data, seq: undefined }]);

  function setSaveStatus(text) {
    saveStatusText = text;
    const node = alive ? container.querySelector("#onb-save-status") : null;
    if (node) node.textContent = text;
  }

  /**
   * PUT draft. 성공하면 true. 실패는 화면에 알리고 false를 돌려주므로 호출자는 이동을 멈춘다.
   * 모든 저장은 saveChain에 줄을 세워 하나씩 보낸다. version은 "보내는 순간"의 값을 읽으므로
   * 앞 요청의 응답 version이 항상 다음 요청에 반영된다.
   * @param {number} forStep 저장할 currentStep(이동하려는 단계)
   * @param {{silent?: boolean, keepalive?: boolean}} [options] silent: 자동 저장(오류를 폼 배너 대신 상태 문구로만 표시)
   */
  function saveDraft(forStep, options = {}) {
    const run = saveChain.then(() => doSave(forStep, options));
    saveChain = run.catch(() => false);
    return run;
  }

  async function doSave(forStep, { silent = false, keepalive = false, userInitiated = false } = {}) {
    // 줄 서 있던 저장이 실제로 나가는 순간에 확인한다. 큐에 넣을 때가 아니라 여기서 막아야
    // 충돌 처리 중에 이미 대기하던 자동·이탈 저장도 무효가 된다.
    if (loggingOut) return false; // 로그아웃 중에는 줄 서 있던 저장도 모두 버린다
    if ((stopped && !userInitiated) || conflict) return false; // 충돌 중에는 사용자가 고르기 전까지 서버에 아무것도 보내지 않는다
    const data = draftForSave();
    const target = alive ? form : null;
    if (draftBytes(data) > MAX_DRAFT_BYTES) {
      if (silent) setSaveStatus("내용이 많아 임시 저장하지 못했어요");
      else if (target) setFormError(target, "입력한 내용이 너무 많아 임시 저장할 수 없어요. 항목을 줄여 주세요.");
      return false;
    }
    const body = { currentStep: forStep, draftData: data };
    if (version !== null) body.version = version; // 처음 저장할 때는 생략(API §4.2)
    const snapshot = snapshotOf(forStep, data);
    // 이 요청이 대응하는 사본의 rev(요청을 보내는 순간 저장소에 있는 사본). 성공했을 때 저장소의 사본이 아직 이 rev일 때만 지운다.
    const revAtSend = myRev;
    try {
      const saved = await api.put("/api/goals/onboarding/draft", {
        body,
        keepalive: keepalive && draftBytes(data) < KEEPALIVE_MAX_BYTES,
      });
      version = saved.version;
      savedSnapshot = snapshot;
      // 방금 저장한 내용이 지금 화면과 같으면 로컬 사본은 필요 없다. 저장하는 사이 더 바뀌었다면 최신 입력으로 사본을 갱신한다.
      // 늦게 도착한 성공 응답이 그 뒤에 쓰인 더 새로운 사본(새 화면·다른 탭)을 지우지 않도록 rev가 같을 때만 지운다.
      if (snapshotOf(forStep, draftForSave()) === snapshot) clearUnsavedDraftIfRev(owner, revAtSend);
      else persistUnsaved();
      setSaveStatus("저장됨");
      // 저장하는 동안 또 바뀌었다면 다시 저장한다(디바운스 타이머가 줄 세워서 보낸다).
      if (alive && !finished && !stopped && !conflict && snapshotOf(step, draftForSave()) !== snapshot) scheduleAutosave();
      return true;
    } catch (error) {
      if (error.code === "VERSION_CONFLICT") {
        await resolveDraftConflict();
      } else if (silent || !alive) {
        // 자동 저장·화면 이탈 저장의 실패는 입력을 방해하지 않도록 조용히 상태 문구만 바꾼다.
        // 401은 api.js가 로그인 화면으로 보내므로 따로 알릴 필요가 없다.
        if (alive) setSaveStatus("저장하지 못했어요");
      } else {
        handleApiError(error, { form: target });
      }
      return false;
    }
  }

  /** 입력이 바뀔 때마다 부른다. 마지막 입력 0.8초 뒤에 한 번만 저장한다. */
  function scheduleAutosave() {
    if (finished || !alive) return;
    persistUnsaved(); // 서버 저장이 막힌 상태(멈춤·충돌)에서도 입력은 로컬에 남긴다
    if (stopped || conflict) return;
    clearTimeout(autosaveTimer);
    autosaveTimer = setTimeout(runAutosave, AUTOSAVE_MS);
  }

  function runAutosave() {
    autosaveTimer = null;
    if (finished || stopped || conflict || !alive) return;
    if (busy) return scheduleAutosave(); // 단계 이동·완료가 진행 중이면 끝난 뒤로 미룬다
    const data = draftForSave();
    if (snapshotOf(step, data) === savedSnapshot) return; // 바뀐 것이 없으면 보내지 않는다
    if (!hasContent(data) && version === null) return; // 서버에 아무것도 없고 입력도 없으면 만들지 않는다
    setSaveStatus("저장 중…");
    saveDraft(step, { silent: true });
  }

  /** 화면을 떠나는 순간(앱 안 이동, 탭 숨김·닫기)에 아직 안 보낸 변경을 보낸다. */
  function flushOnLeave({ keepalive }) {
    if (finished) return;
    persistUnsaved(); // 저장 요청이 도착하기 전에 닫혀도 입력이 남도록 먼저 로컬에 쓴다
    if (stopped || conflict) return;
    clearTimeout(autosaveTimer);
    autosaveTimer = null;
    const data = draftForSave();
    if (snapshotOf(step, data) === savedSnapshot) return;
    if (!hasContent(data) && version === null) return;
    saveDraft(step, { silent: true, keepalive });
  }

  // 탭을 닫거나 숨길 때는 화면 이동처럼 기다릴 수 없으므로 keepalive 요청으로 최대한 보낸다(실패해도 앞선 자동 저장본은 남는다).
  const onPageHide = () => flushOnLeave({ keepalive: true });
  const onVisibility = () => {
    if (document.visibilityState === "hidden") flushOnLeave({ keepalive: true });
  };
  // 입력·선택 변경은 이벤트 위임으로 한 곳에서 받는다(행마다 연결하지 않아도 모든 입력칸이 포함된다).
  // container(#view)는 다른 화면과 공유되므로 아래 cleanup에서 반드시 해제한다.
  container.addEventListener("input", scheduleAutosave);
  container.addEventListener("change", scheduleAutosave);
  window.addEventListener("pagehide", onPageHide);
  document.addEventListener("visibilitychange", onVisibility);
  // 명시적 로그아웃: auth.logout()이 사본을 지우기 "전에" 이 이벤트를 보낸다. 이후 이 화면은 자동 저장·대기 중인 저장·
  // 로컬 사본 쓰기를 모두 멈춘다. 멈추지 않으면 화면 정리(flushOnLeave)가 방금 지운 사본을 다시 만든다.
  // (401 세션 만료는 이 이벤트를 보내지 않으므로 사본이 그대로 남는다.)
  const onLogout = () => {
    loggingOut = true;
    clearTimeout(autosaveTimer);
    autosaveTimer = null;
  };
  window.addEventListener("aim2day:logout", onLogout);

  /**
   * 409: 다른 탭·기기가 먼저 저장했거나 임시 저장이 지워졌다. 서버의 최신 draft를 확인한다.
   *  - 내용이 지금 입력과 같으면(내 keepalive 저장이 늦게 도착한 경우 등) 충돌이 아니므로 version만 맞추고 조용히 이어간다.
   *  - 다르면 사용자가 방금 입력한 내용은 화면에 그대로 두고 자동 저장을 멈춘 뒤, 직접 고르게 한다(충돌 안내).
   *    서버 내용으로 조용히 덮으면 방금 친 글이 사라지고, 내 내용으로 조용히 덮으면 다른 곳의 저장이 사라지기 때문이다.
   *  - 서버에 draft가 없으면(다른 탭에서 완료하거나 지움) handleDraftGone이 처리한다.
   */
  async function resolveDraftConflict({ retry = false } = {}) {
    // 먼저 막는다: 이 await 동안 줄 서 있던 다른 저장이 낡은 version으로 나가지 않게 한다.
    conflict = true;
    // 재시도 때는 localBase를 건드리지 않는다(이미 정해진 "입력이 기반으로 한 version"이다).
    if (!retry) localBase = version;
    clearTimeout(autosaveTimer);
    autosaveTimer = null;
    try {
      const latest = await api.get("/api/goals/onboarding/draft");
      if (!alive || !route.isActive()) return; // 화면을 떠났다면 안내를 다시 그리지 않는다
      conflictUnconfirmed = false;
      if (!latest) {
        conflict = false;
        form?.querySelector("[data-conflict-notice]")?.remove();
        await handleDraftGone();
        return;
      }
      const latestStep = Math.min(4, Math.max(1, Number(latest.currentStep) || 1));
      const theirs = normalizeDraft(latest.draftData);
      sanitizeRefs(theirs, ctx);
      pruneBlank(theirs);
      const mine = snapshotOf(step, draftForSave());
      if (mine === snapshotOf(latestStep, theirs)) {
        version = latest.version ?? null;
        savedSnapshot = mine;
        conflict = false;
        clearUnsavedDraftIfRev(owner, myRev); // 자동 정리이므로 내가 쓴 사본만 지운다
        if (retry) {
          form?.querySelector("[data-conflict-notice]")?.remove();
          renderStep(); // 충돌 안내가 막아 둔 버튼을 되살린다
        }
        return;
      }
      persistUnsaved();
      renderConflictNotice();
    } catch (error) {
      // 서버 최신 내용을 확인하지 못했다. 풀면 대기 중인 완료가 덮어쓸 수 있으므로 충돌을 유지하고 [다시 확인]을 안내한다.
      if (!alive) return;
      conflict = true;
      conflictUnconfirmed = true;
      persistUnsaved(); // 입력은 로컬에 남긴다
      renderConflictNotice();
      handleApiError(error, { form });
    }
  }

  /**
   * 서버에 draft가 없는 것이 확인됐을 때(다른 탭에서 온보딩을 완료했거나 지움).
   * 이 탭의 version은 낡은 것이다. 자동으로 version 없이 다시 저장하면 완료된 임시 저장을 되살리므로,
   * 모든 저장을 멈추고 온보딩 상태를 다시 확인한다. 이미 끝났다면 대시보드로 보내고, 아니면 사용자가 버튼을 눌러야만 새로 작성한다.
   */
  async function handleDraftGone() {
    // 먼저 멈춘다: 이 await 이후에 줄 선 저장들이 doSave에서 모두 무효가 된다.
    stopped = true;
    clearTimeout(autosaveTimer);
    autosaveTimer = null;
    let needsOnboarding = true;
    try {
      needsOnboarding = (await api.get("/api/goals/onboarding/status")).needsOnboarding;
    } catch {
      /* 상태를 못 읽으면 완료 여부를 모르므로 아래의 "다시 작성하기" 안내로 사용자가 정하게 한다 */
    }
    if (!alive || !route.isActive()) return;
    if (!needsOnboarding) {
      finished = true;
      clearLocal();
      toast("다른 곳에서 이미 첫 계획을 완료했어요. 대시보드로 이동합니다.", { error: true });
      navigate("#/dashboard", { replace: true });
      return;
    }
    showStoppedNotice();
  }

  /** 충돌 안내와 두 선택 버튼. 단계를 다시 그려도 유지되도록 renderStep에서도 호출한다. 사용자 입력 값은 쓰지 않는다. */
  function renderConflictNotice() {
    if (!alive || !conflict || !form) return;
    form.querySelector("[data-conflict-notice]")?.remove();
    if (conflictUnconfirmed) {
      // 서버 최신 내용을 확인하지 못한 상태: 선택지 대신 [다시 확인]만 보여 주고, 확인되기 전에는 완료·이동을 막는다.
      const retryBtn = el("button", {
        type: "button",
        class: "btn btn-small",
        text: "다시 확인",
        testid: "conflict-retry",
        onclick: retryConflictCheck,
      });
      form.prepend(
        el(
          "div",
          { class: "form-error", role: "alert", "data-conflict-notice": true, testid: "onboarding-conflict" },
          el("p", { text: "다른 곳에서 저장된 내용이 있어요." }),
          el("p", { text: "저장된 내용을 확인하지 못했어요. 연결을 확인한 뒤 다시 시도해 주세요.", "data-conflict-error": true }),
          el("div", { class: "btn-row" }, retryBtn),
        ),
      );
      for (const id of ["#onb-next", "#onb-back", "#onb-restart"]) {
        const node = container.querySelector(id);
        if (node) node.disabled = true;
      }
      return;
    }
    const keep = el("button", {
      type: "button",
      class: "btn btn-small",
      text: "내 입력으로 저장",
      testid: "conflict-keep-mine",
      onclick: keepMine,
    });
    const load = el("button", {
      type: "button",
      class: "btn btn-outline btn-small",
      text: "저장된 내용 불러오기",
      testid: "conflict-load-server",
      onclick: loadServer,
    });
    form.prepend(
      el(
        "div",
        { class: "form-error", role: "alert", "data-conflict-notice": true, testid: "onboarding-conflict" },
        el("p", { text: "다른 곳에서 저장된 내용이 있어요." }),
        el("div", { class: "btn-row" }, keep, load),
      ),
    );
    // 고르기 전에는 단계 이동·완료·처음부터 다시를 막는다(나중에 하기는 입력이 로컬에 남으므로 허용).
    for (const id of ["#onb-next", "#onb-back", "#onb-restart"]) {
      const node = container.querySelector(id);
      if (node) node.disabled = true;
    }
  }

  /** [다시 확인]: 서버 최신 draft를 다시 읽어 충돌 안내를 정상 형태로 바꾼다. */
  async function retryConflictCheck() {
    if (!conflict || resolvingConflict) return;
    resolvingConflict = true;
    const btn = container.querySelector('[data-testid="conflict-retry"]');
    if (btn) btn.disabled = true;
    try {
      await resolveDraftConflict({ retry: true });
    } finally {
      resolvingConflict = false;
    }
  }

  /** [내 입력으로 저장]: 서버의 최신 version을 받아 지금 입력을 그 위에 저장하고 자동 저장을 다시 켠다. */
  async function keepMine() {
    if (!conflict || resolvingConflict) return;
    resolvingConflict = true;
    setConflictButtonsDisabled(true);
    try {
      const latest = await api.get("/api/goals/onboarding/draft");
      if (!alive || !route.isActive()) return;
      if (!latest) {
        // 그 사이 서버 draft가 사라졌다: 되살리지 않고 멈춤 처리(완료 여부 확인)로 보낸다.
        conflict = false;
        form.querySelector("[data-conflict-notice]")?.remove();
        await handleDraftGone();
        return;
      }
      version = latest.version ?? null;
      savedSnapshot = null; // 서버 내용과 다르다는 것을 확정했으므로 "변경 없음"으로 건너뛰지 않게 한다
      conflict = false;
      const ok = await saveDraft(step, { userInitiated: true });
      if (!alive || !route.isActive()) return;
      if (ok) {
        toast("내 입력으로 저장했어요.");
        renderStep();
      } else if (!conflict && !stopped) {
        // 저장 실패(네트워크 등): 입력과 안내를 유지한 채 다시 고르게 한다.
        conflict = true;
        renderConflictNotice();
      }
      // 다시 409가 나면 resolveDraftConflict가 conflict를 다시 켜고 안내를 그렸다.
    } catch (error) {
      if (alive) {
        conflict = true;
        setConflictButtonsDisabled(false);
        handleApiError(error, { form });
      }
    } finally {
      resolvingConflict = false;
      if (alive && conflict) setConflictButtonsDisabled(false);
    }
  }

  /** [저장된 내용 불러오기]: 서버 draft로 화면을 바꾸고 로컬 사본을 버린다. */
  async function loadServer() {
    if (!conflict || resolvingConflict) return;
    resolvingConflict = true;
    setConflictButtonsDisabled(true);
    try {
      const latest = await api.get("/api/goals/onboarding/draft");
      if (!alive || !route.isActive()) return;
      conflict = false;
      clearLocal();
      if (!latest) {
        form.querySelector("[data-conflict-notice]")?.remove();
        await handleDraftGone();
        return;
      }
      adoptServerDraft(latest);
      toast("저장된 내용을 불러왔어요. 이어서 진행해 주세요.");
      renderStep();
    } catch (error) {
      if (alive) {
        setConflictButtonsDisabled(false);
        handleApiError(error, { form });
      }
    } finally {
      resolvingConflict = false;
    }
  }

  function setConflictButtonsDisabled(disabled) {
    for (const id of ["conflict-keep-mine", "conflict-load-server"]) {
      const node = container.querySelector(`[data-testid="${id}"]`);
      if (node) node.disabled = disabled;
    }
  }

  /** 저장이 멈춘 상태를 알리는 배너. 단계를 다시 그려도 유지되도록 renderStep에서도 호출한다. 사용자 입력 값은 쓰지 않는다. */
  function showStoppedNotice() {
    if (!alive || !stopped || !form) return;
    form.querySelector("[data-stopped-notice]")?.remove();
    const resume = el("button", {
      type: "button",
      class: "btn btn-secondary btn-small",
      text: "다시 작성 시작하기",
      testid: "onboarding-resume",
      onclick: resumeWriting,
    });
    form.prepend(
      el(
        "div",
        { class: "form-error", role: "alert", "data-stopped-notice": true, testid: "onboarding-stopped" },
        el("p", { text: "서버의 임시 저장이 지워져서 저장을 멈췄어요. 다른 곳에서 이미 지웠거나 처음부터 다시 시작했을 수 있어요. 지금 입력한 내용은 이 화면에 남아 있어요." }),
        resume,
      ),
    );
    for (const id of ["#onb-next", "#onb-back", "#onb-restart"]) {
      const node = container.querySelector(id);
      if (node) node.disabled = true;
    }
  }

  /** 사용자가 직접 누른 경우에만 version 없이 새 임시 저장을 만든다. */
  async function resumeWriting() {
    if (!stopped) return;
    stopped = false;
    version = null;
    savedSnapshot = null;
    const ok = await saveDraft(step, { userInitiated: true });
    if (!alive || !route.isActive()) return;
    if (ok) {
      toast("이 화면의 내용으로 다시 작성을 시작해요.");
      renderStep();
    }
  }

  // ---------- 단계 렌더링 ----------
  function periodText(forStep) {
    if (forStep === 1) return `${settings.currentMonth.month.slice(0, 4)}년`;
    if (forStep === 2) return settings.currentMonth.month.replace("-", ".");
    if (forStep === 3) return `${formatDate(settings.currentWeek.startDate)} – ${formatDate(settings.currentWeek.endDate)}`;
    return formatDate(settings.today);
  }

  function questionText(forStep) {
    if (forStep === 1) return status.firstQuestion;
    if (forStep === 2) return "그 목표를 위해 이번 달에 이루고 싶은 건 무엇인가요?";
    if (forStep === 3) return "이번 주에는 어떤 결과를 만들고 싶나요?";
    return "그러면 오늘 해내고 싶은 행동은 무엇인가요?";
  }

  function renderStep() {
    if (!alive || !route.isActive()) return; // 화면을 떠난 뒤에는 공유 #view를 다시 그리지 않는다
    ensureRows();
    container.innerHTML = `
      <div class="onb-top">
        <button type="button" class="link-button" id="onb-later" data-testid="onboarding-later">← 나중에 하기</button>
        <span id="onb-save-status" class="note" data-testid="onboarding-save-status"></span>
        <span id="onb-step-label" class="onb-step-label" data-testid="onboarding-step-label"></span>
      </div>
      <div class="onb-bars" aria-hidden="true"></div>
      <section class="card onb-card">
        <p class="onb-period" data-testid="onboarding-period"></p>
        <h1 id="onb-question" class="onb-question" tabindex="-1" data-testid="onboarding-question"></h1>
        <p class="lead onb-hint"></p>
        <form id="onb-form" novalidate>
          <p class="form-error" data-form-error role="alert" hidden></p>
          <div id="onb-extra"></div>
          <div id="onb-rows"></div>
          <button type="button" id="onb-add" class="btn btn-secondary btn-small" data-testid="onboarding-add-row"></button>
          <p class="note" id="onb-note" role="status"></p>
          <div class="onb-actions">
            <button type="button" id="onb-restart" class="link-button" data-testid="onboarding-restart">처음부터 다시</button>
            <div class="onb-nav">
              <button type="button" id="onb-back" class="btn btn-outline" data-testid="onboarding-back">이전</button>
              <button type="submit" id="onb-next" class="btn"></button>
            </div>
          </div>
        </form>
      </section>`;

    container.querySelector("#onb-save-status").textContent = saveStatusText;
    container.querySelector("#onb-step-label").textContent = `첫 방문 계획 ${step} / 4`;
    const bars = container.querySelector(".onb-bars");
    for (let i = 1; i <= 4; i++) bars.append(el("div", { class: i <= step ? "onb-bar on" : "onb-bar" }));
    container.querySelector(".onb-period").textContent = periodText(step);
    container.querySelector("#onb-question").textContent = questionText(step);
    container.querySelector(".onb-hint").textContent = STEP_HINTS[step - 1];

    form = container.querySelector("#onb-form");
    rowsHost = container.querySelector("#onb-rows");
    const nextButton = container.querySelector("#onb-next");
    nextButton.textContent = step === 4 ? "계획 완료" : `다음: ${["", "이번 달 목표", "이번 주 결과", "오늘 행동"][step]}`;
    nextButton.dataset.testid = step === 4 ? "onboarding-complete" : "onboarding-next";
    container.querySelector("#onb-back").disabled = step === 1;
    container.querySelector("#onb-add").textContent = ["", "+ 연간 목표 추가", "+ 이번 달 목표 추가", "+ 이번 주 목표 추가", "+ 행동 추가"][step];

    container.querySelector("#onb-later").addEventListener("click", () => leave());
    container.querySelector("#onb-restart").addEventListener("click", () => restart());
    container.querySelector("#onb-back").addEventListener("click", () => move(step - 1));
    container.querySelector("#onb-add").addEventListener("click", () => {
      addRow();
      renderRows();
      scheduleAutosave();
      rowsHost.querySelector(".onb-row:last-child input, .onb-row:last-child select")?.focus();
    });
    form.addEventListener("submit", (event) => {
      event.preventDefault();
      if (step === 4) complete();
      else move(step + 1, { validate: true });
    });

    renderExtra();
    renderRows();
    showStoppedNotice(); // 멈춘 상태에서 다시 그려도 안내와 버튼 잠금을 유지한다
    renderConflictNotice(); // 충돌 중에 다시 그려도 입력은 그대로, 안내와 버튼 잠금을 유지한다
    container.querySelector("#onb-question").focus({ preventScroll: true });
  }

  /** 현재 단계 목록이 비어 있으면 빈 행 하나를 둔다. 입력칸이 보여야 사용자가 무엇을 할지 알 수 있다. */
  function ensureRows() {
    const name = ["", "yearlyGoals", "monthlyGoals", "weeklyGoals", "actions"][step];
    if (draft[name].length === 0 && !(step === 1 && yearLimit().remaining === 0)) addRow();
  }

  function currentList() {
    return draft[["", "yearlyGoals", "monthlyGoals", "weeklyGoals", "actions"][step]];
  }

  function addRow() {
    if (step === 1) {
      if (draft.yearlyGoals.length >= yearLimit().remaining) return;
      draft.yearlyGoals.push({ tempId: nextTempId(draft, "y"), title: "" });
    } else if (step === 2) {
      draft.monthlyGoals.push({ tempId: nextTempId(draft, "m"), title: "", description: "" });
    } else if (step === 3) {
      draft.weeklyGoals.push({ tempId: nextTempId(draft, "w"), title: "", measurementType: "CHECKLIST", targetValue: "" });
    } else {
      draft.actions.push({
        type: monthlyOptions(draft, ctx).length > 0 ? "GOAL" : "MAINTENANCE",
        title: "",
        performedDate: settings.today,
        contributionValue: "",
      });
    }
  }

  /** 선택지가 하나뿐이면 사용자가 고르지 않아도 그것으로 정한다(명세 "하나면 자동 연결"과 같은 방향). */
  function autoSelect() {
    sanitizeRefs(draft, ctx);
    const yearly = yearlyOptions(draft, ctx);
    const monthly = monthlyOptions(draft, ctx);
    if (yearly.length === 1) for (const m of draft.monthlyGoals) if (!refKey(m, "yearlyGoal")) setRef(m, "yearlyGoal", yearly[0].key);
    if (monthly.length === 1) {
      for (const w of draft.weeklyGoals) if (!refKey(w, "monthlyGoal")) setRef(w, "monthlyGoal", monthly[0].key);
      for (const a of draft.actions) if (a.type === "GOAL" && !refKey(a, "monthlyGoal")) setRef(a, "monthlyGoal", monthly[0].key);
    }
  }

  /** 1단계: 이미 만든 올해 목표 목록과 한도 안내 */
  function renderExtra() {
    const extra = container.querySelector("#onb-extra");
    extra.innerHTML = "";
    if (step !== 1) return;
    const { max, active } = yearLimit();
    if (ctx.existingYearly.length > 0) {
      const list = el("ul", { class: "onb-existing", "data-testid": "onboarding-existing-yearly" });
      for (const goal of ctx.existingYearly) list.append(el("li", { text: goal.title }));
      extra.append(
        el("p", { class: "note", text: "이미 만든 올해 목표예요. 아래에서 이어서 월간 목표를 연결할 수 있어요." }),
        list,
      );
    }
    extra.append(
      el("p", {
        class: "note",
        text: `연간 목표는 기존 목표를 포함해 한 해에 최대 ${max}개까지 만들 수 있어요. 진행·완료·보류·중단 목표를 모두 세요.`,
      }),
    );
    if (active >= max) {
      extra.append(
        el("p", {
          class: "form-error",
          role: "status",
          "data-testid": "onboarding-limit",
          text: `연간 목표가 이미 ${active}개로 최대 ${max}개예요. 새 목표를 추가하려면 기존 목표를 정리한 뒤 다시 시도해 주세요. 아래 단계에서 기존 목표에 이어서 계획할 수 있어요.`,
        }),
      );
    }
  }

  function renderRows() {
    autoSelect();
    rowsHost.innerHTML = "";
    const list = currentList();
    list.forEach((item, index) => rowsHost.append(buildRow(item, index)));

    const addButton = container.querySelector("#onb-add");
    if (step === 1) {
      const { remaining } = yearLimit();
      addButton.hidden = remaining === 0;
      addButton.disabled = list.length >= remaining;
      const { active, max } = yearLimit();
      container.querySelector("#onb-note").textContent = `연간 목표 (최대 ${max}개, 현재 ${active + list.length}개)`;
    } else {
      addButton.hidden = false;
      addButton.disabled = false;
      container.querySelector("#onb-note").textContent = {
        2: "월간 목표는 이번 달 기간으로 만들어져요. 이미 이번 달 목표가 있다면 이 단계를 비워 두고 넘어가도 돼요.",
        3: "주간 목표는 한 월간 목표에 연결돼요. 이번 주 기간으로 만들어져요.",
        4: "연결된 주간 목표가 하나면 자동으로 연결하고, 여러 개면 직접 고릅니다. 그 밖의 할 일은 목표 경로 없이 저장돼요.",
      }[step];
    }
  }

  function removeRow(index) {
    currentList().splice(index, 1);
    renderRows();
    scheduleAutosave();
  }

  function rowShell(index, rowTestPrefix, ...children) {
    return el(
      "div",
      { class: "onb-row", role: "group", "aria-label": `항목 ${index + 1}` },
      el(
        "div",
        { class: "onb-row-head" },
        el("span", { class: "onb-row-no", text: `항목 ${index + 1}` }),
        el("button", {
          type: "button",
          class: "link-button",
          text: "삭제",
          "aria-label": `항목 ${index + 1} 삭제`,
          testid: `${rowTestPrefix}-remove-${index}`,
          onclick: () => removeRow(index),
        }),
      ),
      ...children,
    );
  }

  function buildRow(item, index) {
    if (step === 1) return buildYearlyRow(item, index);
    if (step === 2) return buildMonthlyRow(item, index);
    if (step === 3) return buildWeeklyRow(item, index);
    return buildActionRow(item, index);
  }

  function buildYearlyRow(item, index) {
    const name = `yearlyGoals[${index}].title`;
    return rowShell(
      index,
      "yearly",
      field(
        "연간 목표 제목",
        textInput({
          name,
          value: item.title,
          maxlength: 100,
          testid: `yearly-title-${index}`,
          placeholder: "예: 백엔드 개발자 취업 준비",
          onInput: (value) => (item.title = value),
        }),
        name,
      ),
    );
  }

  function buildMonthlyRow(item, index) {
    const parentName = `monthlyGoals[${index}].parent`;
    const titleName = `monthlyGoals[${index}].title`;
    const descName = `monthlyGoals[${index}].description`;
    return rowShell(
      index,
      "monthly",
      field(
        "연결할 연간 목표",
        selectInput({
          name: parentName,
          options: yearlyOptions(draft, ctx),
          value: refKey(item, "yearlyGoal"),
          testid: `monthly-parent-${index}`,
          onChange: (key) => setRef(item, "yearlyGoal", key),
        }),
        parentName,
      ),
      field(
        "이번 달에 이루고 싶은 것",
        textInput({
          name: titleName,
          value: item.title,
          maxlength: 100,
          testid: `monthly-title-${index}`,
          placeholder: "예: 알고리즘 문제 40개 풀기",
          onInput: (value) => (item.title = value),
        }),
        titleName,
      ),
      field(
        "설명 / 기대 결과 (선택)",
        textInput({
          name: descName,
          value: item.description,
          maxlength: 500,
          testid: `monthly-description-${index}`,
          onInput: (value) => (item.description = value),
        }),
        descName,
      ),
    );
  }

  function buildWeeklyRow(item, index) {
    const parentName = `weeklyGoals[${index}].parent`;
    const titleName = `weeklyGoals[${index}].title`;
    const typeName = `weeklyGoals[${index}].measurementType`;
    const targetName = `weeklyGoals[${index}].targetValue`;

    const targetField = field(
      "목표값",
      textInput({
        name: targetName,
        value: item.targetValue,
        type: "number",
        testid: `weekly-target-${index}`,
        placeholder: "예: 10",
        attrs: { min: "0", step: "any", inputmode: "decimal" },
        onInput: (value) => (item.targetValue = value),
      }),
      targetName,
      "수치형일 때 이번 주에 채울 양이에요. 0보다 커야 해요.",
    );
    targetField.hidden = item.measurementType !== "NUMERIC";

    return rowShell(
      index,
      "weekly",
      field(
        "연결할 월간 목표",
        selectInput({
          name: parentName,
          options: monthlyOptions(draft, ctx),
          value: refKey(item, "monthlyGoal"),
          testid: `weekly-parent-${index}`,
          onChange: (key) => setRef(item, "monthlyGoal", key),
        }),
        parentName,
      ),
      field(
        "이번 주 결과",
        textInput({
          name: titleName,
          value: item.title,
          maxlength: 100,
          testid: `weekly-title-${index}`,
          placeholder: "예: 알고리즘 문제 10개 풀기",
          onInput: (value) => (item.title = value),
        }),
        titleName,
      ),
      radioGroup({
        name: typeName,
        legend: "측정 방식",
        value: item.measurementType === "NUMERIC" ? "NUMERIC" : "CHECKLIST",
        choices: [
          { value: "CHECKLIST", label: "체크리스트", testid: `weekly-type-checklist-${index}` },
          { value: "NUMERIC", label: "수치형", testid: `weekly-type-numeric-${index}` },
        ],
        onChange: (value) => {
          item.measurementType = value;
          targetField.hidden = value !== "NUMERIC"; // 이 행만 바뀌므로 전체를 다시 그리지 않는다(입력 포커스 유지)
        },
      }),
      targetField,
    );
  }

  function buildActionRow(item, index) {
    const typeName = `actions[${index}].type`;
    const targetName = `actions[${index}].target`;
    const weeklyName = `actions[${index}].weekly`;
    const titleName = `actions[${index}].title`;
    const dateName = `actions[${index}].performedDate`;
    const valueName = `actions[${index}].contributionValue`;
    const parts = [];

    parts.push(
      radioGroup({
        name: typeName,
        legend: "먼저 선택",
        value: item.type === "MAINTENANCE" ? "MAINTENANCE" : "GOAL",
        choices: [
          { value: "GOAL", label: "이번 달 목표", testid: `action-type-goal-${index}` },
          { value: "MAINTENANCE", label: "그 밖의 할 일", testid: `action-type-maintenance-${index}` },
        ],
        onChange: (value) => {
          item.type = value;
          if (value === "MAINTENANCE") {
            setRef(item, "monthlyGoal", "");
            setRef(item, "weeklyGoal", "");
            item.contributionValue = "";
          }
          renderRows(); // 목표 선택칸이 나타나거나 사라지므로 행을 다시 그린다
        },
      }),
    );

    let numericWeekly = false;
    if (item.type !== "MAINTENANCE") {
      const monthlyKey = actionMonthlyKey(draft, ctx, item);
      parts.push(
        field(
          "이번 달 목표",
          selectInput({
            name: targetName,
            options: monthlyOptions(draft, ctx),
            value: monthlyKey,
            testid: `action-monthly-${index}`,
            onChange: (key) => {
              setRef(item, "monthlyGoal", key);
              setRef(item, "weeklyGoal", ""); // 월간이 바뀌면 주간 후보도 달라진다
              renderRows();
            },
          }),
          targetName,
        ),
      );
      const candidates = weeklyOptionsFor(draft, ctx, monthlyKey);
      if (candidates.length > 1) {
        parts.push(
          field(
            "연결할 주간 목표",
            selectInput({
              name: weeklyName,
              options: candidates,
              value: refKey(item, "weeklyGoal"),
              testid: `action-weekly-${index}`,
              onChange: (key) => {
                setRef(item, "weeklyGoal", key);
                renderRows();
              },
            }),
            weeklyName,
            "이 월간 목표에 이번 주 목표가 여러 개예요. 하나를 골라 주세요.",
          ),
        );
      } else if (monthlyKey && candidates.length === 1) {
        parts.push(el("p", { class: "field-hint", text: `주간 목표 "${candidates[0].label}"에 자동으로 연결돼요.` }));
      } else if (monthlyKey.startsWith("t:")) {
        parts.push(el("p", { class: "field-hint", text: "이 월간 목표에는 아직 이번 주 목표가 없어요. 이전 단계에서 먼저 만들어 주세요." }));
      } else if (monthlyKey) {
        parts.push(el("p", { class: "field-hint", text: "이번 주 목표를 서버가 찾아 연결해요. 없으면 안내가 나와요." }));
      }
      numericWeekly = Boolean(resolveWeekly(draft, ctx, item)?.numeric);
    }

    parts.push(
      field(
        "행동",
        textInput({
          name: titleName,
          value: item.title,
          maxlength: 255,
          testid: `action-title-${index}`,
          placeholder: "예: 알고리즘 문제 2개 풀기",
          onInput: (value) => (item.title = value),
        }),
        titleName,
      ),
    );

    // 목표 연결 행동은 이번 주 안에서만 만들 수 있다(API 결정 17). 그 밖의 할 일은 날짜 제한이 없다.
    const dateAttrs = item.type === "MAINTENANCE" ? {} : { min: settings.currentWeek.startDate, max: settings.currentWeek.endDate };
    parts.push(
      field(
        "수행일",
        textInput({
          name: dateName,
          value: item.performedDate,
          type: "date",
          testid: `action-date-${index}`,
          attrs: dateAttrs,
          onInput: (value) => (item.performedDate = value),
        }),
        dateName,
      ),
    );

    if (numericWeekly) {
      parts.push(
        field(
          "완료 시 기여 수치",
          textInput({
            name: valueName,
            value: item.contributionValue,
            type: "number",
            testid: `action-contribution-${index}`,
            attrs: { min: "0", step: "any", inputmode: "decimal" },
            onInput: (value) => (item.contributionValue = value),
          }),
          valueName,
          "연결된 주간 목표가 수치형이에요. 이 행동을 완료하면 주간 목표에 더해지는 양이에요. 0보다 커야 해요.",
        ),
      );
    }
    return rowShell(index, "action", ...parts);
  }

  // ---------- 검증(서버가 최종 검증하므로 헛요청을 줄이는 간단한 확인만) ----------
  const REQUIRED_PARENT = "연결할 상위 목표를 선택해 주세요.";

  function validateStep(forStep) {
    clearFormErrors(form);
    let firstBad = null;
    const fail = (name, message) => {
      setFieldError(form, name, message);
      firstBad = firstBad || form.querySelector(`[name="${name}"]`);
    };

    if (forStep === 2) {
      draft.monthlyGoals.forEach((m, i) => {
        if (!isBlank(m) && !refKey(m, "yearlyGoal")) fail(`monthlyGoals[${i}].parent`, REQUIRED_PARENT);
      });
    } else if (forStep === 3) {
      draft.weeklyGoals.forEach((w, i) => {
        if (isBlank(w)) return;
        if (!refKey(w, "monthlyGoal")) fail(`weeklyGoals[${i}].parent`, REQUIRED_PARENT);
        if (w.measurementType === "NUMERIC" && !(Number(w.targetValue) > 0)) {
          fail(`weeklyGoals[${i}].targetValue`, "수치형 목표값은 0보다 커야 해요.");
        }
      });
    } else if (forStep === 4) {
      draft.actions.forEach((a, i) => {
        if (isBlank(a)) return;
        if (!a.performedDate) fail(`actions[${i}].performedDate`, "수행일을 선택해 주세요.");
        if (a.type === "MAINTENANCE") return;
        const monthlyKey = actionMonthlyKey(draft, ctx, a);
        if (!monthlyKey) return fail(`actions[${i}].target`, "연결할 이번 달 목표를 선택해 주세요.");
        const candidates = weeklyOptionsFor(draft, ctx, monthlyKey);
        if (candidates.length > 1 && !resolveWeekly(draft, ctx, a)) {
          fail(`actions[${i}].weekly`, "연결할 주간 목표를 선택해 주세요.");
        } else if (candidates.length === 0 && monthlyKey.startsWith("t:")) {
          fail(`actions[${i}].target`, "이 월간 목표에는 이번 주 목표가 없어요. 이전 단계에서 먼저 만들어 주세요.");
        }
        const value = a.contributionValue;
        if (value !== "" && value !== undefined && !(Number(value) > 0)) {
          fail(`actions[${i}].contributionValue`, "기여 수치는 0보다 커야 해요.");
        }
      });
    }

    // 다음 단계가 부모로 쓸 항목이 하나도 없으면 넘어갈 수 없다.
    if (!firstBad && forStep < 4 && !hasParentsForStep(draft, ctx, forStep + 1)) {
      setFormError(
        form,
        forStep === 1 ? "이번 달 목표를 연결할 올해 목표를 하나 이상 입력해 주세요." : "이번 주 목표를 연결할 이번 달 목표를 하나 이상 입력해 주세요.",
      );
      return false;
    }
    if (firstBad) {
      firstBad.focus();
      return false;
    }
    return true;
  }

  // ---------- 이동 ----------
  /** 다음·이전. 이동하기 전에 임시 저장하고, 저장이 실패하면 이동하지 않는다. */
  async function move(target, { validate = false } = {}) {
    if (busy || conflict || target < 1 || target > 4) return;
    if (validate && !validateStep(step)) return;
    clearFormErrors(form);
    pruneBlank(draft);
    sanitizeRefs(draft, ctx);
    busy = true;
    const button = container.querySelector("#onb-next");
    setBusy(button, true, "저장 중…");
    const ok = await saveDraft(target);
    busy = false;
    if (!route.isActive()) return;
    if (!ok) {
      // 충돌 처리에서 화면을 다시 그렸을 수 있으므로 현재 버튼만 되돌린다.
      const current = container.querySelector("#onb-next");
      if (current === button) setBusy(button, false);
      showStoppedNotice(); // 저장이 멈춘 상태라면 방금 되돌린 버튼을 다시 잠근다
      renderConflictNotice(); // 이 저장에서 충돌이 났다면 안내와 잠금을 다시 건다
      return;
    }
    step = target;
    renderStep();
    window.scrollTo(0, 0);
  }

  /** 나중에 하기: 지금까지의 입력을 임시 저장으로 남기고 대시보드로 나간다. 입력한 것이 없으면 저장 없이 나간다. */
  async function leave() {
    if (busy) return;
    pruneBlank(draft);
    // 저장이 멈춘 상태에서는 저장을 시도하지 않고(되살리기 방지) 바로 나간다.
    if (!stopped && !conflict && (hasContent(draft) || version !== null)) {
      busy = true;
      const ok = await saveDraft(step);
      busy = false;
      if (!ok) return;
    }
    if (!route.isActive()) return;
    // 사용자가 직접 미룬 것이므로 대시보드가 온보딩으로 되돌려 보내지 않고 "이어서 작성하기" 안내만 보이게 한다.
    deferOnboarding();
    navigate("#/dashboard");
  }

  /** 처음부터 다시: 임시 저장을 지우고 1단계 빈 상태로 돌아간다. 되돌릴 수 없으므로 확인 모달을 거친다. */
  async function restart() {
    if (busy || stopped || conflict) return;
    const ok = await confirmDialog({
      title: "처음부터 다시 할까요?",
      message: "지금까지 입력한 내용과 임시 저장이 모두 지워져요.",
      confirmText: "처음부터 다시",
    });
    if (!ok || !route.isActive()) return;
    busy = true;
    clearTimeout(autosaveTimer);
    try {
      await saveChain; // 보내는 중인 자동 저장이 삭제 뒤에 도착해 draft를 되살리지 않게 먼저 끝낸다
      // 기다리는 동안 충돌(409)·중단·완료·이탈이 생겼다면 삭제하지 않는다(충돌 안내에서 사용자가 고르기 전에는 서버를 건드리지 않는다).
      if (conflict || stopped || finished || !alive || !route.isActive()) return;
      await api.delete("/api/goals/onboarding/draft");
      clearLocal();
      if (!alive || !route.isActive()) return;
      draft = emptyDraft();
      version = null;
      savedSnapshot = null;
      saveStatusText = "";
      step = 1;
      renderStep();
      toast("처음부터 다시 시작해요.");
    } catch (error) {
      if (alive) handleApiError(error, { form });
    } finally {
      busy = false;
    }
  }

  // ---------- 완료 ----------
  async function complete() {
    if (busy || stopped || conflict || !validateStep(4)) return; // 저장이 멈춘 동안에는 "다시 작성 시작하기"를 먼저 눌러야 한다
    pruneBlank(draft); // 빈 행을 없애야 draft 인덱스와 서버 오류 경로(actions[0]...)가 일치한다
    sanitizeRefs(draft, ctx);
    const body = buildCompleteBody(draft, ctx);
    if (!hasContent(draft)) {
      setFormError(form, "만들 항목이 없어요. 한 가지 이상 입력해 주세요.");
      renderRows();
      return;
    }
    busy = true;
    const button = container.querySelector("#onb-next");
    setBusy(button, true, "만드는 중…");
    try {
      clearTimeout(autosaveTimer);
      await saveChain; // 진행 중인 자동 저장이 끝난 뒤에 완료한다(완료 뒤 draft가 다시 생기지 않게)
      // 기다리는 사이 저장이 409로 충돌했거나 중단·완료·이탈됐다면 완료를 보내지 않는다. 충돌 안내가 이미 떠 있으므로 버튼만 되돌린다.
      if (conflict || stopped || finished || !alive || !route.isActive()) {
        if (alive) setBusy(button, false);
        return;
      }
      await api.post("/api/goals/onboarding/complete", { body });
      finished = true; // 서버가 임시 저장을 지웠으므로 이후 자동 저장·이탈 저장을 끈다
      clearLocal();
      clearOnboardingDeferred();
      toast("계획을 만들었어요. 대시보드에서 오늘 행동을 확인해 보세요.");
      if (route.isActive()) navigate("#/dashboard");
    } catch (error) {
      busy = false;
      if (!route.isActive()) return;
      setBusy(button, false); // 단계를 다시 그리는 오류 처리가 새 버튼을 만들기 전에 현재 버튼을 되돌린다
      await showCompleteError(error);
    } finally {
      busy = false;
    }
  }

  /** 서버의 fieldErrors 경로를 (단계, 입력칸 이름)으로 바꾼다. 부모 참조 필드는 화면의 선택칸 이름으로 합친다. */
  function mapServerField(path) {
    const match = /^(yearlyGoals|monthlyGoals|weeklyGoals|actions)\[(\d+)\](?:\.(.+))?$/.exec(path);
    if (!match) return null;
    const [, list, index, rawProp = ""] = match;
    let prop = rawProp;
    if (/^(yearlyGoal|monthlyGoal)(TempId|Id)$/.test(rawProp)) prop = list === "actions" ? "target" : "parent";
    else if (/^weeklyGoal(TempId|Id)$/.test(rawProp)) prop = "weekly";
    return { step: LIST_STEP[list], name: `${list}[${index}].${prop}` };
  }

  async function showCompleteError(error) {
    if (error.code === "VALIDATION_FAILED") {
      const mapped = (error.details?.fieldErrors ?? [])
        .map((e) => ({ ...e, target: mapServerField(e.field) }))
        .filter((e) => e.target);
      if (mapped.length > 0) {
        // 가장 앞 단계의 오류부터 보여 준다. 그 단계의 입력칸에 표시하고 거기로 이동한다.
        step = Math.min(...mapped.map((e) => e.target.step));
        renderStep();
        const here = mapped.filter((e) => e.target.step === step).map((e) => ({ field: e.target.name, reason: e.reason }));
        if (showFieldErrors(form, here, error.message) === 0) setFormError(form, error.message);
        return;
      }
      setFormError(form, error.message);
      return;
    }
    if (error.code === "GOAL_LIMIT_EXCEEDED") {
      // 다른 곳에서 연간 목표가 늘어난 경우. 개수를 다시 읽고 1단계에서 줄이게 한다.
      try {
        const yearly = await api.get("/api/goals/yearly");
        if (!alive || !route.isActive()) return;
        ctx.existingYearly = yearly.items ?? [];
        status.activeYearlyGoalCount = yearly.activeCount;
        status.maxYearlyGoalCount = yearly.maxCount;
        draft.yearlyGoals = draft.yearlyGoals.slice(0, yearLimit().remaining);
      } catch {
        /* 개수 갱신에 실패해도 오류 안내는 계속한다 */
      }
      step = 1;
      renderStep();
      if (alive) setFormError(form, error.message);
      return;
    }
    // 422(부모 사용 불가·기간 불일치·주간 목표 없음/여러 개 등)와 그 밖의 오류는 서버 message를 그대로 안내한다.
    handleApiError(error, { form });
  }

  load().catch((error) => {
    if (!route.isActive()) return;
    container.innerHTML = `<h1 class="page-title">목표 세우기</h1><p class="form-error" role="alert"></p>`;
    container.querySelector(".form-error").textContent = error.message || "화면을 불러오지 못했어요.";
    handleApiError(error);
  });

  // 화면 정리: 앱 안에서 다른 화면으로 가는 순간 아직 못 보낸 입력을 저장하고 리스너를 해제한다.
  return () => {
    flushOnLeave({ keepalive: false });
    alive = false;
    clearTimeout(autosaveTimer);
    window.removeEventListener("aim2day:logout", onLogout);
    container.removeEventListener("input", scheduleAutosave);
    container.removeEventListener("change", scheduleAutosave);
    window.removeEventListener("pagehide", onPageHide);
    document.removeEventListener("visibilitychange", onVisibility);
  };
}
