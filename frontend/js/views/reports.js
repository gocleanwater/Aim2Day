// 리포트 (#/reports). 기준 월 → 그 달에 끝나는 기간 탭(월간·분기·연간) → 기간별 리포트와 직접 회고.
// 호출 API: GET /api/settings(이번 달 확인), GET /api/reports/tabs?month, GET /api/reports?periodType&period,
//           PUT /api/reports/reviews/{periodType}/{period}
import { api } from "../api.js";
import { el, formatDate, handleApiError, renderLoading, setBusy, setFormError, showFieldErrors, clearFormErrors, toast } from "../ui.js";

// 회고 입력칸 하나의 최대 글자 수(서버 검증과 같다). 글자 수 표시와 제출 전 확인에 쓴다.
const REVIEW_MAX = 5000;
const DEFAULT_COLOR = "#3B82F6";
// 서버가 준 색 문자열을 CSS에 넣기 전에 #RRGGBB 형식만 통과시킨다. 아니면 기본색(스타일 속성에 임의 문자열이 들어가는 것을 막는다).
const safeColor = (color) => (/^#[0-9a-fA-F]{6}$/.test(color || "") ? color : DEFAULT_COLOR);

// "YYYY-MM"에 달 수(음수 가능)를 더한다.
function shiftMonth(month, delta) {
  const [y, m] = month.split("-").map(Number);
  return new Date(Date.UTC(y, m - 1 + delta, 1)).toISOString().slice(0, 7);
}

/** 행동 응답의 goalPath({yearlyGoal, monthlyGoal, weeklyGoal})를 "연간 › 월간 › 주간"으로 이어 붙인다. */
function pathText(goalPath) {
  if (!goalPath) return "";
  return [goalPath.yearlyGoal?.title, goalPath.monthlyGoal?.title, goalPath.weeklyGoal?.title].filter(Boolean).join(" › ");
}

// 달성률 변화(%p)를 부호와 함께 표시한다. 양수는 "+"를 붙이고 0·음수는 숫자 그대로다.
const changeText = (pp) => `${pp > 0 ? "+" : ""}${pp}%p`;

// 화면 진입점. 흐름: 이번 달 확인(init) -> 기준 월의 탭 조회(loadTabs) -> 선택한 탭의 리포트 조회(loadReport) -> 회고 입력.
// 기준 월이나 탭을 빠르게 바꿔도 마지막 선택의 응답만 반영하도록 tabsSeq·reportSeq로 요청에 번호를 매긴다.
export function render(container, route) {
  renderLoading(container);
  const alive = () => route.isActive();

  const state = {
    currentMonth: "", // 서버가 계정 시간대로 정한 이번 달. 미래 달 이동을 막는 기준
    month: "", // 기준 월
    tabs: [],
    tab: null, // 선택한 탭
    report: null, // 선택한 탭의 리포트 응답
  };
  // 월·탭을 빠르게 바꿀 때 늦게 온 이전 응답이 화면을 덮어쓰지 않게 하는 요청 번호.
  let tabsSeq = 0;
  let reportSeq = 0;
  const ui = {};

  // 이번 달(서버가 계정 시간대로 정한 값)을 받아 기준 월의 초깃값과 선택 상한으로 쓴다.
  async function init() {
    const settings = await api.get("/api/settings");
    if (!alive()) return;
    state.currentMonth = settings.currentMonth.month;
    state.month = state.currentMonth;
    buildLayout();
    await loadTabs();
  }

  // ---------- 뼈대 ----------
  // 기준 월 선택(이전·다음 버튼, 연·월 선택칸), 탭 목록, 리포트 패널의 골격을 만든다.
  function buildLayout() {
    ui.prev = el("button", { type: "button", class: "btn btn-outline btn-small", text: "◀", "aria-label": "이전 달", testid: "report-prev", onclick: () => moveMonth(shiftMonth(state.month, -1)) });
    ui.next = el("button", { type: "button", class: "btn btn-outline btn-small", text: "▶", "aria-label": "다음 달", testid: "report-next", onclick: () => moveMonth(shiftMonth(state.month, 1)) });
    // 브라우저마다 모양이 다른 <input type="month"> 대신 한국어 "2026년"·"11월" 선택칸 둘을 쓴다.
    ui.year = el("select", { id: "report-month-year", "aria-label": "기준 연도", testid: "report-month-year" });
    ui.monthSelect = el("select", { "aria-label": "기준 월 선택", testid: "report-month-month" });
    ui.year.addEventListener("change", () => {
      // 연도를 바꾸면 같은 월을 유지하되, 이번 연도라면 이번 달을 넘지 않게 맞춘다.
      const month = Number(state.month.slice(5, 7));
      const limit = Number(ui.year.value) === Number(state.currentMonth.slice(0, 4)) ? Number(state.currentMonth.slice(5, 7)) : 12;
      moveMonth(`${ui.year.value}-${String(Math.min(month, limit)).padStart(2, "0")}`);
    });
    ui.monthSelect.addEventListener("change", () => moveMonth(`${state.month.slice(0, 4)}-${String(ui.monthSelect.value).padStart(2, "0")}`));
    ui.tabs = el("div", { class: "report-tabs", role: "tablist", "aria-label": "리포트 기간" });
    ui.message = el("p", { class: "form-error", role: "alert", hidden: true });
    ui.panel = el("div", { class: "report-page", role: "tabpanel", id: "report-panel", tabindex: "-1" });

    container.replaceChildren(
      el(
        "div",
        { class: "report-page" },
        el("h1", { class: "page-title", style: "margin:0", text: "리포트" }),
        el("p", { class: "note", style: "margin:0", text: "기록한 행동을 돌아보고 다음 기간의 방향을 정해요. 그 밖의 할 일은 집계하지 않아요." }),
        el(
          "section",
          { class: "card" },
          el("div", { class: "report-month-nav" }, el("span", { style: "font-weight:600;font-size:14px", text: "기준 월" }), ui.prev, ui.year, ui.monthSelect, ui.next),
          ui.tabs,
        ),
        ui.message,
        ui.panel,
      ),
    );
    syncMonthControls();
  }

  /**
   * 선택칸 내용을 현재 기준 월에 맞춰 다시 채운다.
   * 범위: (이번 연도 - 2)년 ~ 이번 달. 사용자 가입 연도는 API에 없어 쓰지 않고 고정 폭으로 정했다.
   * 미래 달은 서버도 422로 막지만, 월 선택지를 이번 달까지만 만들어 화면에서 먼저 막는다.
   */
  function syncMonthControls() {
    const currentYear = Number(state.currentMonth.slice(0, 4));
    const currentMonthNo = Number(state.currentMonth.slice(5, 7));
    const year = Number(state.month.slice(0, 4));
    const monthNo = Number(state.month.slice(5, 7));
    const firstYear = currentYear - 2;
    ui.year.replaceChildren();
    for (let y = firstYear; y <= currentYear; y++) ui.year.append(el("option", { value: String(y), text: `${y}년` }));
    ui.year.value = String(year);
    ui.monthSelect.replaceChildren();
    const lastMonth = year === currentYear ? currentMonthNo : 12;
    for (let m = 1; m <= lastMonth; m++) ui.monthSelect.append(el("option", { value: String(m), text: `${m}월` }));
    ui.monthSelect.value = String(monthNo);
    ui.prev.disabled = year <= firstYear && monthNo <= 1;
    ui.next.disabled = state.month >= state.currentMonth;
  }

  // 리포트 영역의 오류 표시. 화면 안에 서버 message를 보이고 공통 오류 규칙(401 처리 등)도 적용한다.
  function showError(error) {
    ui.message.textContent = error.message || "리포트를 불러오지 못했어요.";
    ui.message.hidden = false;
    handleApiError(error);
  }

  // ---------- 기준 월과 탭 ----------
  // 기준 월 이동. 이번 달 이후나 선택 범위 밖이면 무시하고, 바뀌면 탭 목록부터 다시 조회한다.
  function moveMonth(month) {
    if (month > state.currentMonth) return;
    if (Number(month.slice(0, 4)) < Number(state.currentMonth.slice(0, 4)) - 2) return; // 선택 범위 밖
    state.month = month;
    syncMonthControls();
    loadTabs();
  }

  // 기준 월에서 끝나는 기간 탭(월간·분기·연간)을 받는다. 탭이 바뀌면 이미 보낸 리포트 요청은 reportSeq를 올려 응답을 버리게 한다.
  async function loadTabs() {
    const seq = ++tabsSeq;
    ++reportSeq; // 이미 날아간 리포트 요청의 응답은 버린다
    ui.message.hidden = true;
    try {
      const data = await api.get("/api/reports/tabs", { query: { month: state.month } });
      if (!alive() || seq !== tabsSeq) return;
      state.tabs = data.tabs ?? [];
      // 기준 월을 바꾸면 항상 첫 탭(월간)에서 시작하고 바로 조회한다(API §4.12).
      state.tab = state.tabs[0] ?? null;
      drawTabs();
      if (state.tab) await loadReport();
      else ui.panel.replaceChildren(el("p", { class: "note", text: "표시할 리포트 기간이 없어요." }));
    } catch (error) {
      if (!alive() || seq !== tabsSeq) return;
      state.tabs = [];
      drawTabs();
      ui.panel.replaceChildren();
      showError(error);
    }
  }

  // 탭 버튼을 그린다. WAI-ARIA 탭 패턴에 맞춰 선택된 탭만 tab 순서에 두고 방향키로 이동한다.
  function drawTabs() {
    ui.tabs.replaceChildren(
      ...state.tabs.map((tab) => {
        const selected = state.tab && tab.periodType === state.tab.periodType;
        return el("button", {
          type: "button",
          class: "report-tab",
          role: "tab",
          text: tab.label,
          "aria-selected": selected ? "true" : "false",
          "aria-controls": "report-panel",
          tabindex: selected ? "0" : "-1",
          testid: `report-tab-${tab.periodType}`,
          onclick: () => selectTab(tab),
          onkeydown: (event) => {
            // 탭 목록의 표준 키보드 조작: 좌우 화살표로 이웃 탭, Home·End로 처음·끝.
            const index = state.tabs.indexOf(tab);
            let next = null;
            if (event.key === "ArrowRight") next = state.tabs[(index + 1) % state.tabs.length];
            else if (event.key === "ArrowLeft") next = state.tabs[(index - 1 + state.tabs.length) % state.tabs.length];
            else if (event.key === "Home") next = state.tabs[0];
            else if (event.key === "End") next = state.tabs[state.tabs.length - 1];
            if (!next) return;
            event.preventDefault();
            selectTab(next, true);
          },
        });
      }),
    );
  }

  // 탭 선택. 같은 탭이면 무시하고, 바뀌면 그 기간의 리포트를 새로 받는다. 키보드 이동이면 새 탭에 포커스를 둔다.
  function selectTab(tab, focus = false) {
    if (state.tab && tab.periodType === state.tab.periodType) return;
    state.tab = tab;
    drawTabs();
    if (focus) ui.tabs.querySelector('[aria-selected="true"]')?.focus();
    loadReport();
  }

  // ---------- 리포트 ----------
  // 선택한 탭의 리포트(GET /api/reports)를 받아 그린다.
  async function loadReport() {
    const seq = ++reportSeq;
    const tab = state.tab;
    renderLoading(ui.panel);
    try {
      const report = await api.get("/api/reports", { query: { periodType: tab.periodType, period: tab.period } });
      if (!alive() || seq !== reportSeq) return;
      state.report = report;
      drawReport(tab, report);
    } catch (error) {
      if (!alive() || seq !== reportSeq) return;
      ui.panel.replaceChildren();
      showError(error);
    }
  }

  // 기간 안내, 요약, 완료한 행동·결과물, 목표별 달성률 변화, 직접 회고 순으로 패널을 채운다.
  function drawReport(tab, report) {
    ui.panel.replaceChildren(
      el("p", { class: "note", style: "margin:0", testid: "report-period", text: `${tab.label} 리포트 · ${formatDate(report.startDate)} – ${formatDate(report.endDate)}` }),
      summarySection(report),
      actionsSection(report),
      goalsSection(report),
      reviewSection(tab, report),
    );
  }

  // 요약 수치 두 개: 완료한 핵심 행동 수, 기록된 활동일 수.
  function summarySection(report) {
    const stat = (label, value, testid) =>
      el("div", { class: "report-stat" }, el("p", { text: label }), el("strong", { text: value, testid }));
    return el(
      "section",
      { class: "report-summary", "aria-label": "요약" },
      stat("완료한 핵심 행동", `${report.summary.completedActionCount}개`, "report-action-count"),
      stat("기록된 활동일", `${report.summary.activeDayCount}일`, "report-active-days"),
    );
  }

  // 이 기간에 완료한 행동 목록과 기록된 결과물. 결과물은 행동 id로 제목을 찾아 어느 행동의 것인지 함께 보인다.
  function actionsSection(report) {
    const actions = report.actions ?? [];
    const results = report.results ?? [];
    const titleOf = new Map(actions.map((a) => [a.id, a.title]));
    return el(
      "section",
      { class: "card" },
      el("h2", { text: "무엇을 했나요?" }),
      el(
        "div",
        { testid: "report-actions" },
        actions.length === 0
          ? el("p", { class: "note", text: "이 기간에 완료한 핵심 행동이 없어요." })
          : el(
              "ul",
              { class: "list-plain" },
              actions.map((a) =>
                el(
                  "li",
                  { class: "action-row", testid: `report-action-${a.id}` },
                  el(
                    "div",
                    { class: "action-main" },
                    el("div", { class: "action-title", text: a.title }),
                    el("p", { class: "action-meta", text: [formatDate(a.performedDate), pathText(a.goalPath)].filter(Boolean).join(" · ") }),
                  ),
                ),
              ),
            ),
      ),
      el("h3", { style: "margin:20px 0 8px;font-size:15px", text: "기록된 결과물" }),
      el(
        "div",
        { testid: "report-results" },
        results.length === 0
          ? el("p", { class: "note", text: "기록된 결과물 없음" })
          : el(
              "ul",
              { class: "list-plain" },
              results.map((r) =>
                el(
                  "li",
                  { class: "action-row" },
                  el(
                    "div",
                    { class: "action-main" },
                    el("div", { class: "action-title", text: r.resultSummary }),
                    el("p", { class: "action-meta", text: [formatDate(r.performedDate), titleOf.get(r.actionId)].filter(Boolean).join(" · ") }),
                  ),
                ),
              ),
            ),
      ),
    );
  }

  // ---------- 목표별 달성률 변화 ----------
  // 연간 목표별 달성률 변화 영역. 각 카드는 goalCard가 그린다.
  function goalsSection(report) {
    const goals = report.goalProgress ?? [];
    return el(
      "section",
      { class: "card" },
      el("h2", { text: "올해 목표는 얼마나 가까워졌나요?" }),
      el("p", { class: "note", style: "margin:-6px 0 12px", text: "왼쪽은 시작 기준, 오른쪽은 이번 기간 말(진행 중이면 오늘)의 달성률이에요." }),
      goals.length === 0
        ? el("p", { class: "note", testid: "report-goals-empty", text: "비교할 연간 목표가 없어요." })
        : el("div", { class: "report-goals" }, goals.map(goalCard)),
    );
  }

  /**
   * COMPARABLE이고 숫자가 모두 있을 때만 막대 두 개를 그린다. NOT_CONFIGURED·CRITERIA_REVISED는 막대 없이 서버가 준 note만 보여
   * "변화 없음"으로 오해하지 않게 한다(API §4.12). 막대 높이는 0~100으로 잘라 트랙을 넘지 않게 한다.
   */
  function goalCard(goal) {
    const id = goal.yearlyGoalId;
    const color = safeColor(goal.color);
    const head = el("p", { class: "report-goal-name", style: `color:${color}`, text: goal.title });
    const comparable =
      goal.state === "COMPARABLE" && typeof goal.startRate === "number" && typeof goal.endRate === "number";
    if (!comparable) {
      const fallback = goal.state === "NOT_CONFIGURED" ? "달성 기준 설정 전" : goal.state === "CRITERIA_REVISED" ? "기준·배점 변경으로 비교 불가" : "비교할 기록이 없어요.";
      return el("div", { testid: `report-goal-${id}`, "data-state": goal.state }, head, el("p", { class: "report-note", text: goal.note || fallback }));
    }

    const diff = typeof goal.changePp === "number" ? goal.changePp : goal.endRate - goal.startRate;
    const column = (kind, label, rate, opacity) => {
      const clamped = Math.max(0, Math.min(100, rate));
      return el(
        "div",
        { class: "bar-col", testid: `report-bar-${kind}-${id}`, "data-rate": String(rate) },
        el(
          "div",
          { class: "bar-track" },
          el("div", { class: "bar-fill-v", style: `height:${clamped}%;background:${color};opacity:${opacity}` }),
        ),
        el("span", { text: label }),
        el("span", { class: "bar-value", text: `${rate}%` }),
      );
    };
    return el(
      "div",
      { testid: `report-goal-${id}`, "data-state": goal.state },
      head,
      // figure에 요약 aria-label을 줘 막대를 못 보는 사용자도 같은 값을 듣게 한다.
      el(
        "figure",
        { style: "margin:0", role: "img", "aria-label": `${goal.title}: ${goal.startLabel} ${goal.startRate}%, ${goal.endLabel} ${goal.endRate}%, 변화 ${changeText(diff)}` },
        el("div", { class: "bar-pair" }, column("start", goal.startLabel, goal.startRate, 0.4), column("end", goal.endLabel, goal.endRate, 1)),
        el("figcaption", { class: "bar-change", testid: `report-change-${id}`, text: changeText(diff) }),
      ),
      goal.note ? el("p", { class: "action-meta", style: "text-align:center", text: goal.note }) : null,
    );
  }

  // ---------- 직접 회고 ----------
  /**
   * 직접 회고 저장 규칙(API §4.12):
   *  - PUT은 회고 전체를 저장하므로 두 칸 값을 모두 보내고 빈 칸은 null로 보낸다.
   *  - version: 회고가 아직 없으면 생략, 이미 있으면 마지막으로 받은 값을 보낸다(없는데 보내도, 있는데 생략해도 409).
   *  - 409는 다른 곳에서 먼저 저장했다는 뜻이다. 입력 중인 글을 조용히 덮어쓰지 않도록, 사용자가 "최신 회고 불러오기"를 눌러야 바꾼다.
   */
  function reviewSection(tab, report) {
    let review = report.review ?? null; // {improvements, nextFocus, version} | null
    const textArea = (id, name, testid, value) => {
      const area = el("textarea", { class: "text-area", id, name, rows: "4", maxlength: REVIEW_MAX, testid, style: "min-height:110px" });
      area.value = value ?? "";
      return area;
    };
    const improvements = textArea("review-improvements", "improvements", "review-improvements", review?.improvements);
    const nextFocus = textArea("review-next-focus", "nextFocus", "review-next-focus", review?.nextFocus);
    const counter = (area, testid) => el("p", { class: "report-counter", testid });
    const improvementsCount = counter(improvements, "review-improvements-count");
    const nextFocusCount = counter(nextFocus, "review-next-focus-count");
    const updateCounts = () => {
      improvementsCount.textContent = `${improvements.value.length} / ${REVIEW_MAX.toLocaleString("ko-KR")}자`;
      nextFocusCount.textContent = `${nextFocus.value.length} / ${REVIEW_MAX.toLocaleString("ko-KR")}자`;
    };
    improvements.addEventListener("input", updateCounts);
    nextFocus.addEventListener("input", updateCounts);
    updateCounts();

    const save = el("button", { type: "submit", class: "btn", text: "회고 저장", testid: "review-save" });
    const banner = el("p", { class: "form-error", "data-form-error": true, role: "alert", hidden: true });
    const conflict = el("div", { class: "conflict-box", role: "alert", hidden: true, testid: "review-conflict" });
    const field = (labelText, area, count, name) =>
      el(
        "div",
        { class: "field" },
        el("label", { for: area.id, text: labelText }),
        area,
        count,
        el("p", { class: "field-error", "data-error-for": name }),
      );
    const form = el(
      "form",
      { class: "review-form", novalidate: true, testid: "review-form", "aria-label": "직접 돌아보기" },
      el("h2", { style: "margin:0", text: "직접 돌아보기" }),
      el("p", { class: "note", style: "margin:0", text: "이 기간의 회고예요. 탭마다 따로 저장돼요. 직접 쓴 내용만 저장하고 자동으로 채우지 않아요." }),
      banner,
      conflict,
      field("전보다 할 수 있게 된 일", improvements, improvementsCount, "improvements"),
      field("다음 기간에 집중할 일", nextFocus, nextFocusCount, "nextFocus"),
      el("div", {}, save),
    );

    /** 서버의 최신 회고로 입력칸과 version을 바꾼다(충돌 뒤 사용자가 고른 경우에만 호출). */
    async function reloadLatest() {
      try {
        const latest = await api.get("/api/reports", { query: { periodType: tab.periodType, period: tab.period } });
        if (!alive() || state.tab !== tab) return;
        review = latest.review ?? null;
        improvements.value = review?.improvements ?? "";
        nextFocus.value = review?.nextFocus ?? "";
        updateCounts();
        conflict.hidden = true;
        toast("최신 회고를 불러왔어요.");
      } catch (error) {
        handleApiError(error);
      }
    }

    form.addEventListener("submit", async (event) => {
      event.preventDefault();
      clearFormErrors(form);
      conflict.hidden = true;
      if (improvements.value.length > REVIEW_MAX || nextFocus.value.length > REVIEW_MAX) {
        return setFormError(form, `각 칸은 최대 ${REVIEW_MAX.toLocaleString("ko-KR")}자까지 쓸 수 있어요.`);
      }
      const body = {
        improvements: improvements.value.trim() === "" ? null : improvements.value,
        nextFocus: nextFocus.value.trim() === "" ? null : nextFocus.value,
      };
      if (review && typeof review.version === "number") body.version = review.version;

      setBusy(save, true, "저장 중…");
      try {
        const saved = await api.put(`/api/reports/reviews/${tab.periodType}/${tab.period}`, { body });
        setBusy(save, false);
        // 응답 형식이 명세에 없어 version이 응답에 있으면 쓰고, 없으면 리포트를 다시 읽어 최신 version을 얻는다(가정).
        const version = saved?.version ?? saved?.review?.version;
        if (typeof version === "number") {
          review = { improvements: body.improvements, nextFocus: body.nextFocus, version };
        } else {
          const latest = await api.get("/api/reports", { query: { periodType: tab.periodType, period: tab.period } });
          review = latest.review ?? { improvements: body.improvements, nextFocus: body.nextFocus, version: 0 };
        }
        toast("회고를 저장했어요.");
      } catch (error) {
        setBusy(save, false);
        if (error.code === "VERSION_CONFLICT") {
          conflict.replaceChildren(
            el("p", { style: "margin:0 0 8px", text: "다른 곳에서 먼저 저장된 회고가 있어요. 최신 회고를 다시 불러온 뒤 이어서 쓰세요. 불러오면 지금 입력한 내용은 사라지니, 필요하면 먼저 복사해 두세요." }),
            el("button", { type: "button", class: "btn btn-outline btn-small", text: "최신 회고 불러오기", testid: "review-reload", onclick: reloadLatest }),
          );
          conflict.hidden = false;
          return;
        }
        if (error.code === "VALIDATION_FAILED") {
          if (showFieldErrors(form, error.details?.fieldErrors) === 0) setFormError(form, error.message);
          return;
        }
        handleApiError(error, { form });
      }
    });

    return el("section", { class: "card" }, form);
  }

  init().catch((error) => {
    if (!alive()) return;
    container.replaceChildren(
      el("h1", { class: "page-title", text: "리포트" }),
      el("p", { class: "form-error", role: "alert", text: error.message || "리포트를 불러오지 못했어요." }),
    );
    handleApiError(error);
  });
}
