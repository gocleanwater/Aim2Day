// 대시보드 (#/dashboard). 영역별로 API를 나눠 부른다(명세 §5 끝): 한 화면을 여는 것만으로도 RR로 여러 goal-service 인스턴스에 요청이 흩어진다.
//   오늘 행동        GET /api/actions?date=&sort=            (날짜 이동·정렬 때 이 영역만 다시 부른다. 결정 11)
//   해야 할 일·일정   GET /api/calendar/dashboard
//   이번 주 / 이번 달 GET /api/goals/weekly, GET /api/goals/monthly   (요약 영역. 항상 오늘 기준)
//   행동 추가 대상    GET /api/goals/monthly?month=, GET /api/goals/weekly?date=  (보고 있는 날짜 기준. 날짜를 옮기면 다시 읽는다)
//   최근 진행 기록    GET /api/goals/progress-logs
// 영역마다 따로 불러오므로 한 영역이 실패해도 나머지는 그대로 보이고, 실패한 영역에는 [다시 시도]가 나온다.
import { api } from "../api.js";
import { isOnboardingDeferred } from "../auth.js";
import { addDays, isValidIsoDate } from "../dateUtil.js";
import { navigate } from "../navigation.js";
import {
  clearFormErrors,
  confirmDialog,
  el,
  formatDate,
  handleApiError,
  renderLoading,
  setBusy,
  setFieldError,
  setFormError,
  toast,
} from "../ui.js";
import { createQuadrant, pathText, placementText } from "./dashboardQuadrant.js";
import { renderCalendarSummary, renderLogs, renderMonthly, renderWeekly } from "./dashboardSections.js";

const SORTS = [
  ["IMPORTANCE_URGENCY", "중요도+긴급도"],
  ["IMPORTANCE", "중요도 높은 순"],
  ["URGENCY", "긴급도 높은 순"],
  ["GROUP", "목표 / 그 밖의 할 일"],
];

// 화면 진입점. 흐름: 온보딩 상태·설정 확인(init) -> 골격 생성(build) -> 영역별 로드(loadAll).
// 쓰기(행동 추가·완료·삭제)가 끝나면 영향받는 영역만 reloadProgress()로 다시 읽는다. 진행률은 서버가 계산하므로 화면은 값을 직접 고치지 않는다.
export function render(container, route) {
  renderLoading(container);
  const alive = () => route.isActive();

  const state = {
    settings: null, // GET /api/settings (today, currentWeek ...)
    date: null, // 오늘 행동 영역이 보고 있는 날짜
    sort: "IMPORTANCE_URGENCY",
    data: null, // GET /api/actions 응답
    weekly: [], // 이번 주 주간 목표(행동 추가·수정 폼의 선택지이기도 함)
    monthly: [], // 이번 달 월간 목표
    // 행동 추가·수정 폼의 선택지. 요약(위 weekly/monthly)은 "오늘" 기준이지만, 지난 날짜에 행동을 추가할 때는
    // 그 날짜가 속한 달의 월간 목표와 그 날짜가 속한 주의 주간 목표가 후보여야 하므로 선택한 날짜 기준으로 따로 읽는다.
    destMonthly: [],
    destWeekly: [],
    onboardingBanner: null, // "나중에 하기"로 들어온 경우 {hasDraft}
    ambiguous: null, // 422 ACTION_WEEKLY_GOAL_AMBIGUOUS가 알려 준 후보(주간 목표 직접 선택용)
  };
  let quad = null;
  let actionsSeq = 0; // 날짜를 빠르게 바꿀 때 늦게 온 이전 응답이 화면을 덮어쓰지 않게 한다
  let destSeq = 0; // 행동 추가 후보(월간·주간)도 같은 이유로 번호를 매긴다

  const isToday = () => state.date === state.settings.today;
  const isFuture = () => state.date > state.settings.today;
  const inCurrentWeek = (date) => date >= state.settings.currentWeek.startDate && date <= state.settings.currentWeek.endDate;
  const beyondWeek = () => state.date > state.settings.currentWeek.endDate;

  // ---------- 시작: 온보딩 필요 여부와 설정을 먼저 읽는다 ----------
  // 시작: 온보딩 필요 여부와 설정을 병렬로 읽고, 필요하면 온보딩으로 보낸다.
  async function init() {
    const [status, settings] = await Promise.all([api.get("/api/goals/onboarding/status"), api.get("/api/settings")]);
    if (!alive()) return;
    // 온보딩이 필요하면(올해 목표가 없거나 임시 저장이 남음) 온보딩이 먼저다.
    // 단, 사용자가 온보딩에서 [나중에 하기]를 직접 눌러 왔다면(세션 표시) 되돌려 보내지 않는다.
    // 되돌려 보내면 "나중에 하기"가 영원히 동작하지 않기 때문이다. 대신 화면 위에 이어서 작성하라는 안내를 둔다.
    if (status.needsOnboarding) {
      if (!isOnboardingDeferred()) {
        navigate("#/onboarding", { replace: true });
        return;
      }
      state.onboardingBanner = { hasDraft: Boolean(status.hasDraft) };
    }
    state.settings = settings;
    state.date = settings.today;
    build();
    loadAll();
  }

  // ---------- 골격 ----------
  let ui = {};

  // 화면 골격(DOM)을 만들어 ui에 참조를 모아 둔다. 영역별로 따로 채우므로 데이터 없이도 한 번에 그릴 수 있다.
  function build() {
    ui.title = el("h2", { testid: "actions-title" });
    ui.doneCount = el("span", { class: "note", testid: "done-count" });
    ui.dateInput = el("input", { type: "date", id: "action-date", "aria-label": "행동을 볼 날짜", testid: "date-input" });
    ui.dateError = el("p", { class: "field-error", role: "alert", testid: "date-error" });
    ui.dateHint = el("p", { class: "hint-warn", role: "status", testid: "date-hint" });
    ui.goalPill = el("span", { class: "pill", testid: "goal-count" });
    ui.otherPill = el("span", { class: "pill alt", testid: "other-count" });
    ui.sort = el("select", { id: "action-sort", "aria-label": "오늘 행동 정렬", testid: "action-sort" });
    for (const [value, label] of SORTS) ui.sort.append(el("option", { value, text: label }));
    ui.list = el("div", { testid: "action-list" });
    ui.pastDetails = el("details", { class: "fold", testid: "past-incomplete" });
    ui.pastSummary = el("summary", { testid: "past-summary" });
    ui.pastList = el("div");
    ui.quadDetails = el("details", { class: "fold", open: true, testid: "quadrant-area" });
    ui.quadHost = el("div");
    ui.tasks = el("div", { testid: "cal-tasks" });
    ui.events = el("div", { testid: "cal-events" });
    ui.pastEvents = el("div", { testid: "cal-past-events" });
    ui.pastEventsSummary = el("summary");
    ui.weekly = el("div", { testid: "weekly-list" });
    ui.monthly = el("div", { testid: "monthly-list" });
    ui.logs = el("div", { testid: "progress-logs" });
    buildAddForm();

    const dateNav = el(
      "div",
      { class: "date-nav" },
      el("button", { type: "button", class: "btn btn-outline btn-small", text: "◀", "aria-label": "전날", testid: "date-prev", onclick: () => goTo(addDays(state.date, -1)) }),
      ui.dateInput,
      el("button", { type: "button", class: "btn btn-outline btn-small", text: "▶", "aria-label": "다음날", testid: "date-next", onclick: () => goTo(addDays(state.date, 1)) }),
      el("button", { type: "button", class: "btn btn-secondary btn-small", text: "오늘", testid: "date-today", onclick: () => goTo(state.settings.today) }),
    );
    ui.dateInput.addEventListener("change", () => {
      if (!isValidIsoDate(ui.dateInput.value)) {
        ui.dateError.textContent = "올바른 날짜를 입력해 주세요.";
        return;
      }
      goTo(ui.dateInput.value);
    });
    ui.sort.addEventListener("change", () => {
      state.sort = ui.sort.value;
      loadActions();
    });

    ui.pastDetails.append(
      ui.pastSummary,
      el("p", { class: "note", text: "체크하면 원래 날짜의 기록으로 완료돼요. 오늘 하려면 오늘로 옮기세요." }),
      ui.pastList,
    );
    ui.quadDetails.append(el("summary", { text: "오늘의 중요도·긴급도" }), ui.quadHost);
    ui.pastEventsDetails = el("details", { class: "fold" }, ui.pastEventsSummary, ui.pastEvents);

    container.replaceChildren(
      el(
        "div",
        { class: "dash" },
        state.onboardingBanner ? buildOnboardingBanner() : null,
        el(
          "section",
          { class: "card", "aria-labelledby": "actions-title" },
          el(
            "div",
            { class: "dash-head" },
            ui.title,
            ui.doneCount,
          ),
          dateNav,
          ui.dateError,
          ui.dateHint,
          el("p", { class: "note", text: "목표에 연결된 일과 그 밖의 할 일을 따로 확인하고 완료할 수 있어요." }),
          el(
            "div",
            { class: "pill-row" },
            ui.goalPill,
            ui.otherPill,
            el("label", { for: "action-sort", class: "sr-only", text: "정렬" }),
            ui.sort,
          ),
          ui.list,
          ui.addForm,
          ui.weeklyHelp,
          ui.needWeekly,
          ui.beyondHint,
          ui.pastDetails,
          ui.quadDetails,
        ),
        el(
          "section",
          { class: "card", "aria-labelledby": "due-title" },
          el(
            "div",
            { class: "dash-head" },
            el("h2", { id: "due-title", text: "해야 할 일과 일정" }),
            el("a", { href: "#/calendar", class: "btn btn-secondary btn-small", text: "달력에서 보기", testid: "calendar-link" }),
          ),
          el(
            "div",
            { class: "two-col" },
            el("div", {}, el("h3", { text: "해야 할 일" }), el("p", { class: "note", text: "완료할 때까지 모두 보여요." }), ui.tasks),
            el(
              "div",
              {},
              el("h3", { text: "일정" }),
              el("p", { class: "note", text: "오늘부터 7일 안의 일정" }),
              ui.events,
              ui.pastEventsDetails,
            ),
          ),
        ),
        el(
          "section",
          { class: "card week-card", "aria-labelledby": "week-title" },
          el("p", { class: "note", text: `${formatDate(state.settings.currentWeek.startDate)} – ${formatDate(state.settings.currentWeek.endDate)}` }),
          el("h2", { id: "week-title", text: "이번 주 목표" }),
          el("a", { href: "#/plan", text: "이번 주 목표 설정 →", testid: "weekly-plan-link" }),
          ui.weekly,
        ),
        el(
          "section",
          { class: "card", "aria-labelledby": "month-title" },
          el("p", { class: "note", text: state.settings.currentMonth.month.replace("-", ".") }),
          el("h2", { id: "month-title", text: "이번 달 목표" }),
          el("a", { href: "#/plan", text: "이번 달 목표 설정 →", testid: "monthly-plan-link" }),
          ui.monthly,
        ),
        el("section", { class: "card", "aria-labelledby": "log-title" }, el("h2", { id: "log-title", text: "최근 진행 기록" }), ui.logs),
      ),
    );

    quad = createQuadrant({
      host: ui.quadHost,
      state,
      reload: loadActions,
      refreshList: renderList,
    });
  }

  /** [나중에 하기]로 온 사용자에게 보이는 안내. 이어서 작성하기를 누르면 온보딩으로 간다. */
  function buildOnboardingBanner() {
    const hasDraft = state.onboardingBanner.hasDraft;
    return el(
      "section",
      { class: "card", role: "status", testid: "onboarding-banner" },
      el("p", {
        class: "lead",
        text: hasDraft ? "작성하던 계획이 있어요" : "아직 올해 목표가 없어요 · 계획 세우기",
      }),
      el("button", {
        type: "button",
        class: "btn",
        text: hasDraft ? "이어서 작성하기" : "계획 세우기",
        testid: "onboarding-resume",
        onclick: () => navigate("#/onboarding"),
      }),
    );
  }

  // ---------- 영역별 불러오기 ----------
  function sectionError(host, error, retry) {
    if (error.code === "UNAUTHENTICATED") return;
    host.replaceChildren(
      el("p", { class: "form-error", role: "alert", text: error.message || "불러오지 못했어요." }),
      el("button", { type: "button", class: "btn btn-outline btn-small", text: "다시 시도", onclick: retry }),
    );
  }

  // 오늘 행동 영역 조회. actionsSeq로 날짜·정렬을 연속해서 바꿀 때 최신 응답만 반영하고, 성공하면 사분면도 같은 데이터로 갱신한다.
  async function loadActions() {
    const mine = ++actionsSeq;
    try {
      const data = await api.get("/api/actions", { query: { date: state.date, sort: state.sort } });
      if (!alive() || mine !== actionsSeq) return;
      state.data = data;
    } catch (error) {
      if (alive() && mine === actionsSeq) sectionError(ui.list, error, loadActions);
      return;
    }
    renderActionsArea();
    quad.update();
  }

  // 해야 할 일·일정 영역 조회(GET /api/calendar/dashboard). 그리기는 dashboardSections.js가 한다.
  async function loadCalendar() {
    try {
      const data = await api.get("/api/calendar/dashboard");
      if (!alive()) return;
      renderCalendarSummary(
        { tasksHost: ui.tasks, eventsHost: ui.events, pastHost: ui.pastEvents, pastSummary: ui.pastEventsSummary },
        data,
        toggleCalendarItem,
      );
    } catch (error) {
      if (alive()) sectionError(ui.tasks, error, loadCalendar);
    }
  }

  // 이번 주 목표 영역 조회. 결과는 요약 표시와 행동 추가 폼 선택지에 함께 쓴다.
  async function loadWeekly() {
    try {
      const data = await api.get("/api/goals/weekly");
      if (!alive()) return;
      state.weekly = data;
      renderWeekly(ui.weekly, data);
    } catch (error) {
      if (alive()) sectionError(ui.weekly, error, loadWeekly);
    }
  }

  // 이번 달 목표 영역 조회. 이 영역의 실패는 다른 영역을 막지 않는다.
  async function loadMonthly() {
    try {
      const data = await api.get("/api/goals/monthly");
      if (!alive()) return;
      state.monthly = data;
      renderMonthly(ui.monthly, data);
    } catch (error) {
      if (alive()) sectionError(ui.monthly, error, loadMonthly);
    }
  }

  /**
   * 행동 추가 대상 후보를 "보고 있는 날짜" 기준으로 읽는다(API §4.9: 지난 날짜 행동도 그 주에 만든 주간 목표에 연결할 수 있다).
   * 현재 주 이후 날짜는 목표 연결 행동을 만들 수 없으므로(결정 17) 읽지 않고 "그 밖의 할 일"만 남긴다.
   * 날짜를 빠르게 바꿀 수 있어 번호(destSeq)가 최신일 때만 반영한다.
   */
  async function loadDestinations() {
    const mine = ++destSeq;
    const date = state.date;
    if (beyondWeek()) {
      state.destMonthly = [];
      state.destWeekly = [];
      refreshDestinations();
      return;
    }
    try {
      const [monthly, weekly] = await Promise.all([
        api.get("/api/goals/monthly", { query: { month: date.slice(0, 7) } }),
        api.get("/api/goals/weekly", { query: { date } }),
      ]);
      if (!alive() || mine !== destSeq) return;
      // 달 경계(예: 11/30~12/6)에 걸친 주간 목표는 부모 월간 목표가 다른 달에 있어 ?month= 응답에 없다.
      // 주간 응답의 path(yearlyGoal·monthlyGoal)로 부모 월간 목표를 보태, 후보 주간 목표의 부모를 항상 고를 수 있게 한다.
      // id로 중복을 제거하고, 월간 응답의 항목을 우선한다(전체 필드를 가진 원본이다).
      const merged = [...monthly];
      for (const w of weekly) {
        const parent = w.path?.monthlyGoal;
        if (!parent || merged.some((m) => m.id === parent.id)) continue;
        merged.push({ id: parent.id, title: parent.title, month: parent.month, yearlyGoal: w.path.yearlyGoal ?? null });
      }
      state.destMonthly = merged;
      state.destWeekly = weekly;
    } catch (error) {
      if (alive() && mine === destSeq) {
        state.destMonthly = [];
        state.destWeekly = [];
        handleApiError(error);
      }
    }
    if (!alive() || mine !== destSeq) return;
    refreshDestinations();
  }

  // 최근 진행 기록 영역 조회(최근 10건).
  async function loadLogs() {
    try {
      const data = await api.get("/api/goals/progress-logs", { query: { limit: 10 } });
      if (!alive()) return;
      renderLogs(ui.logs, data);
    } catch (error) {
      if (alive()) sectionError(ui.logs, error, loadLogs);
    }
  }

  // 모든 영역을 병렬로 불러온다. 서로 독립이므로 Promise.all로 묶는다.
  function loadAll() {
    return Promise.all([loadActions(), loadCalendar(), loadWeekly(), loadMonthly(), loadLogs(), loadDestinations()]);
  }

  /** 행동 완료·추가·삭제는 주간·월간 진행률과 진행 기록도 바꾸므로 함께 다시 읽는다. */
  function reloadProgress() {
    return Promise.all([loadActions(), loadWeekly(), loadMonthly(), loadLogs()]);
  }

  // ---------- 날짜 이동 (오늘 행동 영역만) ----------
  function goTo(date) {
    if (!isValidIsoDate(date)) {
      ui.dateError.textContent = "올바른 날짜를 입력해 주세요.";
      return;
    }
    ui.dateError.textContent = "";
    state.date = date;
    state.ambiguous = null;
    ui.dateInput.value = date;
    // 이전 날짜의 후보가 잠깐 남아 잘못 선택되지 않도록 비운 뒤 새 날짜의 후보를 읽는다.
    state.destMonthly = [];
    state.destWeekly = [];
    refreshDestinations();
    loadActions();
    loadDestinations();
  }

  // ---------- 오늘 행동 영역 그리기 ----------
  function renderActionsArea() {
    const { data } = state;
    const today = isToday();
    ui.title.textContent = today ? "오늘 행동" : `${formatDate(state.date)} 행동`;
    ui.dateInput.value = state.date;
    ui.dateError.textContent = "";
    ui.sort.value = state.sort;

    const total = data.summary.goal.total + data.summary.maintenance.total;
    const done = data.summary.goal.completed + data.summary.maintenance.completed;
    ui.doneCount.textContent = `${done} / ${total} 완료`;
    ui.goalPill.textContent = `목표에 연결된 일 ${data.summary.goal.completed}/${data.summary.goal.total} 완료`;
    ui.otherPill.textContent = `그 밖의 할 일 ${data.summary.maintenance.completed}/${data.summary.maintenance.total} 완료`;

    ui.dateHint.textContent = today
      ? ""
      : isFuture()
        ? "미래 날짜에는 행동을 미리 추가할 수 있어요. 완료 체크는 그날부터 할 수 있어요."
        : "지난 날짜에는 행동을 추가하고, 체크를 잊은 행동을 완료할 수 있어요.";

    renderList();
    renderPast();
    // 사분면과 지난 미완료는 오늘에만 보인다(결정 11). 다른 날짜는 목록의 상태 문구로 확정 좌표만 읽는다.
    ui.quadDetails.hidden = !today;
    ui.pastDetails.hidden = !today;
    refreshFormVisibility();
  }

  // 오늘 행동 목록. GROUP 정렬이면 목표 연결 행동과 그 밖의 할 일로 나누어 보여 주고, 그 외 정렬은 서버가 준 순서 그대로 보여 준다.
  function renderList() {
    const items = state.data?.items ?? [];
    if (state.sort === "GROUP") {
      const goals = items.filter((a) => a.type === "GOAL");
      const others = items.filter((a) => a.type !== "GOAL");
      ui.list.replaceChildren(
        el("h3", { text: "목표에 연결된 일" }),
        listOf(goals, "목표에 연결된 일이 없어요."),
        el("h3", { text: "그 밖의 할 일" }),
        listOf(others, "그 밖의 할 일이 없어요."),
      );
    } else {
      ui.list.replaceChildren(listOf(items, "이 날짜에는 행동이 없어요."));
    }
  }

  // 행동 배열을 목록으로 바꾼다. 비었으면 안내 문구.
  function listOf(items, emptyText) {
    if (items.length === 0) return el("p", { class: "note", text: emptyText });
    return el("ul", { class: "list-plain" }, items.map(actionRow));
  }

  // 행동 한 줄: 완료 체크, 제목·소속 경로·점 상태, 수정·삭제 버튼. 목표 연결 행동은 목표 색을 왼쪽 테두리에 쓴다.
  function actionRow(a) {
    const check = el("input", { type: "checkbox", "aria-label": `${a.title} 완료`, testid: `action-check-${a.id}` });
    check.checked = a.isCompleted;
    // 미래 날짜의 행동은 완료할 수 없다(ACTION_FUTURE_COMPLETION). 완료 취소는 언제든 가능하다.
    check.disabled = isFuture() && !a.isCompleted;
    check.addEventListener("change", () => toggleAction(a, check));

    const meta = [];
    if (a.type === "GOAL") meta.push(pathText(a));
    if (a.contributionValue !== null && a.contributionValue !== undefined) meta.push(`기여 수치 ${a.contributionValue}`);
    if (a.dueDate) {
      const d = a.daysUntilDue;
      meta.push(`기한 ${formatDate(a.dueDate)}${d === null || d === undefined ? "" : d < 0 ? " (기한 지남)" : d === 0 ? " (오늘)" : ` (D-${d})`}`);
    }
    const row = el(
      "li",
      { class: "action-row", "data-action-id": String(a.id), testid: `action-${a.id}` },
      check,
      el(
        "div",
        { class: "action-main" },
        el(
          "div",
          { class: a.isCompleted ? "action-title done" : "action-title" },
          a.title,
          el("span", { class: a.type === "GOAL" ? "badge" : "badge other", text: a.type === "GOAL" ? "목표" : "그 밖의 일" }),
        ),
        meta.length ? el("p", { class: "action-meta", text: meta.join(" · ") }) : null,
        a.resultSummary ? el("p", { class: "action-meta", text: `결과: ${a.resultSummary}` }) : null,
        el("p", { class: "action-meta", testid: `action-placement-${a.id}`, text: placementText(a) }),
      ),
      el(
        "div",
        { class: "action-buttons" },
        el("button", { type: "button", class: "link-button", text: "수정", "aria-label": `${a.title} 수정`, testid: `action-edit-${a.id}`, onclick: () => openEditDialog(a) }),
        el("button", { type: "button", class: "link-button", text: "삭제", "aria-label": `${a.title} 삭제`, testid: `action-delete-${a.id}`, onclick: () => deleteAction(a) }),
      ),
    );
    if (a.type === "GOAL" && a.displayColor) row.style.borderLeftColor = a.displayColor;
    return row;
  }

  // 지난 미완료 행동 접이식 영역(오늘 날짜를 볼 때만 보인다).
  function renderPast() {
    const past = state.data?.pastIncomplete;
    const items = past?.items ?? [];
    ui.pastSummary.textContent = `지난 미완료 ${past?.count ?? items.length}개`;
    if (items.length === 0) {
      ui.pastList.replaceChildren(el("p", { class: "note", text: "지난 미완료 행동이 없어요." }));
      return;
    }
    ui.pastList.replaceChildren(
      el(
        "ul",
        { class: "list-plain" },
        items.map((a) => {
          const check = el("input", { type: "checkbox", "aria-label": `${a.title} 완료`, testid: `past-check-${a.id}` });
          check.addEventListener("change", () => toggleAction(a, check));
          return el(
            "li",
            { class: "action-row", testid: `past-${a.id}` },
            check,
            el(
              "div",
              { class: "action-main" },
              el("div", { class: "action-title", text: a.title }),
              el("p", { class: "action-meta", text: `${formatDate(a.performedDate)}${a.type === "GOAL" ? ` · ${pathText(a)}` : ""}` }),
            ),
            el(
              "div",
              { class: "action-buttons" },
              el("button", { type: "button", class: "btn btn-secondary btn-small", text: "오늘로 옮기기", testid: `past-move-${a.id}`, onclick: () => moveToToday(a) }),
            ),
          );
        }),
      ),
    );
  }

  // ---------- 행동 완료·삭제·이동 ----------
  async function toggleAction(a, check) {
    const wanted = check.checked;
    check.disabled = true;
    try {
      await api.put(`/api/actions/${a.id}/completion`, { body: { completed: wanted } });
      await reloadProgress();
    } catch (error) {
      // 예: 422 ACTION_FUTURE_COMPLETION — 서버 message를 보여 주고 체크 상태를 되돌린다.
      check.checked = !wanted;
      check.disabled = false;
      handleApiError(error);
    }
  }

  /** 지난 미완료 행동의 수행일을 오늘로 옮긴다(결정 10). 완료와 달리 오늘 목록에 들어온다. */
  async function moveToToday(a) {
    try {
      await api.patch(`/api/actions/${a.id}`, { body: { performedDate: state.settings.today, version: a.version } });
      toast("오늘로 옮겼어요.");
      await reloadProgress();
    } catch (error) {
      handleApiError(error, { onVersionConflict: () => loadActions() });
    }
  }

  // 행동 삭제. 완료한 행동은 목록에서만 숨겨지고 기록은 남는다는 점을 모달에서 알린다. 삭제 뒤 진행률·기록도 다시 읽는다.
  async function deleteAction(a) {
    const ok = await confirmDialog({
      title: "행동을 삭제할까요?",
      message: a.isCompleted
        ? "완료한 행동은 목록에서만 숨겨지고, 완료 기록은 리포트와 진행률에 그대로 남아요. 기록까지 없애려면 완료를 취소한 뒤 삭제하세요."
        : "삭제하면 목록에서 사라져요.",
      confirmText: "삭제",
    });
    if (!ok || !alive()) return;
    try {
      await api.delete(`/api/actions/${a.id}`);
      toast("행동을 삭제했어요.");
      await reloadProgress();
    } catch (error) {
      handleApiError(error, { onVersionConflict: () => loadActions() });
      if (error.code === "RESOURCE_NOT_FOUND") loadActions();
    }
  }

  // 달력 요약의 체크박스 변경. 실패하면 체크 상태를 되돌린다(dashboardSections가 콜백으로 부른다).
  async function toggleCalendarItem(item, checked, check) {
    check.disabled = true;
    try {
      await api.put(`/api/calendar/items/${item.id}/completion`, { body: { completed: checked } });
      await loadCalendar();
    } catch (error) {
      check.checked = !checked;
      check.disabled = false;
      handleApiError(error);
    }
  }

  // ---------- 행동 추가 폼 ----------
  // 이 화면 폼용 필드 묶음. ui.js의 formField와 같은 구조이며, 입력칸 name과 data-error-for가 같아야 서버 필드 오류가 붙는다.
  function formField(labelText, control, name) {
    return el(
      "div",
      { class: "field" },
      el("label", { for: control.id, text: labelText }),
      control,
      el("p", { class: "field-error", "data-error-for": name }),
    );
  }

  // 행동 추가 폼. 목적지는 "월간 목표(m:id)" 또는 "그 밖의 할 일(maintenance)" 중 하나다.
  // 주간 목표와 기여 수치 칸은 목적지·후보 수에 따라 refreshFormVisibility()가 보이거나 숨긴다.
  function buildAddForm() {
    ui.destination = el("select", { id: "add-destination", name: "destination", required: true, testid: "add-action-destination" });
    ui.weeklySelect = el("select", { id: "add-weekly", name: "weeklyGoalId", testid: "add-action-weekly" });
    ui.titleInput = el("input", { id: "add-title", name: "title", type: "text", maxlength: 255, placeholder: "할 일 제목", testid: "add-action-title" });
    ui.contribution = el("input", {
      id: "add-contribution",
      name: "contributionValue",
      type: "number",
      min: "0",
      step: "any",
      inputmode: "decimal",
      testid: "add-action-contribution",
    });
    ui.addSubmit = el("button", { type: "submit", class: "btn", text: "추가", testid: "add-action-submit" });
    ui.weeklyField = formField("연결할 주간 목표", ui.weeklySelect, "weeklyGoalId");
    ui.contributionField = formField("완료 시 기여 수치", ui.contribution, "contributionValue");
    ui.weeklyHint = el("p", { class: "field-hint", role: "status", testid: "add-action-hint" });
    ui.weeklyHelp = el("p", { class: "hint-warn", role: "status", testid: "weekly-help" });
    // 선택한 월간 목표에 보고 있는 날짜의 주간 목표가 하나도 없으면 서버에 보내 봐야 422이므로 화면에서 먼저 막는다.
    // (서버의 ACTION_WEEKLY_GOAL_REQUIRED 처리는 목록이 낡았을 때를 위한 보조 수단으로 남긴다.)
    ui.needWeeklyText = el("span");
    ui.needWeeklyLink = el("a", { href: "#/plan", class: "btn btn-outline btn-small", text: "이번 주 목표 만들기", style: "margin-left:8px" });
    ui.needWeekly = el("p", { class: "hint-warn", role: "status", hidden: true, testid: "add-action-need-weekly" }, ui.needWeeklyText, ui.needWeeklyLink);
    // 현재 주 이후 날짜에는 목표 연결 행동을 만들 수 없다(결정 17).
    ui.beyondHint = el("p", {
      class: "hint-warn",
      role: "status",
      hidden: true,
      testid: "beyond-week-hint",
      text: "다음 주 이후 날짜에는 목표 행동을 추가할 수 없어요. 그 주가 되면 주간 목표를 만든 뒤 추가하세요.",
    });

    ui.addForm = el(
      "form",
      { class: "add-form", novalidate: true, testid: "add-action-form", "aria-label": "행동 추가" },
      el("p", { class: "form-error", "data-form-error": true, role: "alert", hidden: true, style: "grid-column:1/-1" }),
      formField("월간 목표 또는 종류", ui.destination, "destination"),
      ui.weeklyField,
      formField("행동 제목", ui.titleInput, "title"),
      ui.contributionField,
      el("div", { class: "field" }, el("label", { class: "sr-only", text: "추가" }), ui.addSubmit),
      el("div", { style: "grid-column:1/-1" }, ui.weeklyHint),
    );
    ui.destination.addEventListener("change", () => {
      state.ambiguous = null;
      clearFormErrors(ui.addForm);
      refreshFormVisibility();
    });
    ui.weeklySelect.addEventListener("change", refreshFormVisibility);
    ui.addForm.addEventListener("submit", submitAction);
  }

  /** 목적지 선택지: 보고 있는 날짜가 속한 달의 월간 목표들 + 그 밖의 할 일. 현재 주 이후 날짜에서는 월간 목표를 고를 수 없다. */
  function refreshDestinations() {
    if (!ui.destination) return;
    const previous = ui.destination.value;
    ui.destination.replaceChildren(el("option", { value: "", text: beyondWeek() ? "그 밖의 할 일만 가능해요" : isToday() || state.date.slice(0, 7) === state.settings.currentMonth.month ? "이번 달 목표 선택" : "월간 목표 선택", disabled: true }));
    // 서로 다른 달의 월간 목표가 섞이면(달 경계 주) 어느 달 목표인지 알 수 있게 달을 앞에 붙인다.
    const mixedMonths = new Set(state.destMonthly.map((m) => m.month).filter(Boolean)).size > 1;
    for (const m of state.destMonthly) {
      const monthLabel = mixedMonths && m.month ? `[${m.month.replace("-", ".")}] ` : "";
      ui.destination.append(
        el("option", {
          value: `m:${m.id}`,
          text: `${monthLabel}${m.yearlyGoal?.title ?? ""} › ${m.title}`,
          disabled: beyondWeek(),
        }),
      );
    }
    ui.destination.append(el("option", { value: "maintenance", text: "그 밖의 할 일" }));
    const keep = [...ui.destination.options].some((o) => o.value === previous && !o.disabled);
    ui.destination.value = keep ? previous : beyondWeek() ? "maintenance" : state.destMonthly.length === 0 ? "maintenance" : "";
    refreshFormVisibility();
  }

  // 선택한 월간 목표 아래의 주간 목표 후보. 서버가 모호하다고(422) 알려 준 후보가 있으면 그것을 우선한다.
  function weeklyCandidates(monthlyId) {
    if (state.ambiguous) return state.ambiguous;
    // 보고 있는 날짜가 속한 주의 주간 목표(GET /api/goals/weekly?date=)에서 고른다. 최종 판단은 서버가 한다(결정 6).
    return state.destWeekly.filter((w) => w.path?.monthlyGoal?.id === monthlyId);
  }

  /** 선택한 목적지에 따라 주간 목표 선택·기여 수치·안내 문구를 보이거나 숨긴다. */
  function refreshFormVisibility() {
    if (!ui.destination || !state.settings) return;
    ui.beyondHint.hidden = !beyondWeek();
    const dest = ui.destination.value;
    const goal = dest.startsWith("m:");
    const monthlyId = goal ? Number(dest.slice(2)) : null;
    const candidates = goal ? weeklyCandidates(monthlyId) : [];

    ui.weeklyHint.textContent = "";
    ui.weeklyHelp.textContent = "";
    // 후보 주간 목표가 0개면 추가를 막는다. 이번 주가 아닌 날짜에는 #/plan에서 만들 수 있는 것이 아니므로 링크는 숨긴다.
    const needWeekly = goal && candidates.length === 0;
    ui.needWeekly.hidden = !needWeekly;
    ui.addSubmit.disabled = needWeekly;
    if (needWeekly) {
      const current = inCurrentWeek(state.date);
      ui.needWeeklyText.textContent = current
        ? "이번 주 목표를 먼저 만들어 주세요."
        : "그 주에 만들어 둔 주간 목표가 없어요. 주간 목표가 없는 날짜에는 목표 행동을 추가할 수 없어요.";
      ui.needWeeklyLink.hidden = !current;
    }
    ui.weeklyField.hidden = !(goal && candidates.length > 1);
    if (!ui.weeklyField.hidden) {
      const chosen = ui.weeklySelect.value;
      ui.weeklySelect.replaceChildren(
        el("option", { value: "", text: "주간 목표 선택" }),
        ...candidates.map((c) => el("option", { value: String(c.id), text: c.title })),
      );
      ui.weeklySelect.value = candidates.some((c) => String(c.id) === chosen) ? chosen : "";
    }

    let resolved = null;
    if (goal) {
      if (candidates.length === 1) {
        resolved = candidates[0];
        ui.weeklyHint.textContent = `주간 목표 "${resolved.title}"에 자동으로 연결돼요.`;
      } else if (candidates.length > 1 && ui.weeklySelect.value) {
        resolved = candidates.find((c) => String(c.id) === ui.weeklySelect.value) ?? null;
      }
    }
    // 수치형 주간 목표일 때만 기여 수치를 받는다(API §4.9). 후보의 종류는 그 날짜의 주간 목표 목록에서 찾는다.
    const known = resolved ? (state.destWeekly.find((w) => w.id === resolved.id) ?? state.weekly.find((w) => w.id === resolved.id)) : null;
    ui.contributionField.hidden = !(known && known.measurementType === "NUMERIC");
    if (ui.contributionField.hidden) ui.contribution.value = "";
  }

  // 행동 추가 제출. 본문에는 선택에 따라 필요한 필드만 담는다.
  // 그 밖의 할 일은 type=MAINTENANCE만, 목표 연결은 type=GOAL + monthlyGoalId(+ 사용자가 직접 고른 weeklyGoalId, 수치형이면 contributionValue).
  // 성공하면 영향받는 영역을 다시 읽는다.
  async function submitAction(event) {
    event.preventDefault();
    clearFormErrors(ui.addForm);
    const dest = ui.destination.value;
    const title = ui.titleInput.value.trim();
    let invalid = false;
    if (!dest) {
      setFieldError(ui.addForm, "destination", "월간 목표 또는 그 밖의 할 일을 먼저 선택해 주세요.");
      invalid = true;
    }
    if (!title) {
      setFieldError(ui.addForm, "title", "행동 제목을 입력해 주세요.");
      invalid = true;
    }
    if (invalid) return;
    if (ui.addSubmit.disabled) return; // 주간 목표 후보가 없어 막힌 상태(refreshFormVisibility)

    // 연결 주간 목표는 서버가 판단하는 것이 기준이다(결정 6). 후보가 하나뿐이면 월간만 보내 자동 선택에 맡기고,
    // 사용자가 직접 골랐을 때만 weeklyGoalId를 함께 보낸다.
    const body = { title, performedDate: state.date };
    if (dest === "maintenance") {
      body.type = "MAINTENANCE";
    } else {
      body.type = "GOAL";
      body.monthlyGoalId = Number(dest.slice(2));
      if (!ui.weeklyField.hidden && ui.weeklySelect.value) body.weeklyGoalId = Number(ui.weeklySelect.value);
      if (!ui.contributionField.hidden && ui.contribution.value !== "") body.contributionValue = Number(ui.contribution.value);
    }

    setBusy(ui.addSubmit, true, "추가 중…");
    try {
      await api.post("/api/actions", { body });
      ui.titleInput.value = "";
      ui.contribution.value = "";
      state.ambiguous = null;
      toast("행동을 추가했어요.");
      await reloadProgress();
      if (alive()) refreshFormVisibility();
    } catch (error) {
      handleAddError(error);
    } finally {
      setBusy(ui.addSubmit, false);
      // setBusy가 버튼을 다시 켜므로, 후보가 없어 막혀야 하는 상태라면 다시 잠근다.
      if (alive()) refreshFormVisibility();
    }
  }

  // 행동 추가 오류를 code별로 처리한다. 검증 오류는 서버 필드 이름(monthlyGoalId)을 화면 입력칸 이름으로 바꿔 표시하고,
  // 주간 목표 필요·모호 오류는 안내 문구와 후보 선택 UI로 이어 준다.
  function handleAddError(error) {
    const form = ui.addForm;
    switch (error.code) {
      case "VALIDATION_FAILED": {
        // 서버의 필드 이름을 화면의 입력칸 이름으로 바꿔 표시한다.
        const mapped = (error.details?.fieldErrors ?? []).map((e) => ({
          ...e,
          field: e.field === "monthlyGoalId" ? "destination" : e.field,
        }));
        handleApiError(Object.assign(Object.create(error), { details: { fieldErrors: mapped } }), { form });
        return;
      }
      case "ACTION_WEEKLY_GOAL_REQUIRED":
        // 수행일이 이번 주면 "이번 주 목표를 만들어 달라", 지난 주면 "그 주에 만든 목표가 없다"는 서버 message를 그대로 보여 준다.
        setFormError(form, error.message);
        if (error.details?.performedDate && inCurrentWeek(error.details.performedDate)) {
          form.querySelector("[data-form-error]").append(" ", el("a", { href: "#/plan", text: "이번 주 목표 설정" }));
        }
        return;
      case "ACTION_WEEKLY_GOAL_AMBIGUOUS":
        // 후보가 여러 개: 서버가 준 후보로 주간 목표 선택칸을 열어 사용자가 직접 고르게 한다.
        state.ambiguous = error.details?.candidates ?? null;
        setFormError(form, error.message);
        refreshFormVisibility();
        return;
      default:
        handleApiError(error, { form });
    }
  }

  // ---------- 행동 수정 ----------
  function openEditDialog(a) {
    const dialog = el("dialog", { class: "modal", "aria-labelledby": "edit-title", testid: "edit-dialog" });
    const form = el("form", { novalidate: true, method: "dialog" });
    const banner = el("p", { class: "form-error", "data-form-error": true, role: "alert", hidden: true });
    const title = el("input", { id: "edit-title-input", name: "title", type: "text", maxlength: 255, testid: "edit-title" });
    title.value = a.title;
    const date = el("input", { id: "edit-date", name: "performedDate", type: "date", testid: "edit-date" });
    date.value = a.performedDate;
    const summary = el("textarea", {
      id: "edit-summary",
      name: "resultSummary",
      rows: "3",
      maxlength: 2000,
      testid: "edit-summary",
      style: "width:100%;border:1px solid #dfe5eb;border-radius:12px;padding:10px 14px;font:inherit",
    });
    summary.value = a.resultSummary ?? "";
    const parts = [banner, formField("제목", title, "title"), formField("수행일", date, "performedDate")];

    // 연결 주간 목표 이동은 목표 연결 행동만. 선택지는 이번 주 주간 목표 + 지금 연결된 목표.
    let weeklySelect = null;
    let contribution = null;
    let contributionField = null;
    const currentWeekly = a.goalPath?.weeklyGoal ?? null;
    // 이번 주 목표와 보고 있는 날짜의 주간 목표를 합친다(중복 id 제거). 지난 날짜 행동은 그 주의 목표로 옮길 수 있어야 한다.
    const weeklyPool = [...state.weekly, ...state.destWeekly.filter((d) => !state.weekly.some((w) => w.id === d.id))];
    const known = (id) => weeklyPool.find((w) => w.id === id) ?? null;
    if (a.type === "GOAL") {
      const options = [...weeklyPool.map((w) => ({ id: w.id, title: w.title }))];
      if (currentWeekly && !options.some((o) => o.id === currentWeekly.id)) options.unshift({ id: currentWeekly.id, title: `${currentWeekly.title} (현재)` });
      weeklySelect = el("select", { id: "edit-weekly", name: "weeklyGoalId", testid: "edit-weekly" }, options.map((o) => el("option", { value: String(o.id), text: o.title })));
      weeklySelect.value = currentWeekly ? String(currentWeekly.id) : "";
      contribution = el("input", { id: "edit-contribution", name: "contributionValue", type: "number", min: "0", step: "any", inputmode: "decimal", testid: "edit-contribution" });
      contribution.value = a.contributionValue ?? "";
      contributionField = formField("완료 시 기여 수치", contribution, "contributionValue");
      const syncContribution = () => {
        const target = known(Number(weeklySelect.value));
        // 종류를 아는 목표는 그대로 따르고, 모르는(과거 주) 목표는 원래 기여 수치가 있을 때만 보여 준다.
        contributionField.hidden = target ? target.measurementType !== "NUMERIC" : a.contributionValue === null || a.contributionValue === undefined;
      };
      weeklySelect.addEventListener("change", syncContribution);
      syncContribution();
      parts.push(formField("연결된 주간 목표", weeklySelect, "weeklyGoalId"), contributionField);
    }
    parts.push(formField("결과 요약 (리포트의 기록된 결과물)", summary, "resultSummary"));

    const save = el("button", { type: "submit", class: "btn", text: "저장", testid: "edit-save" });
    const cancel = el("button", { type: "button", class: "btn btn-outline", text: "취소", testid: "edit-cancel" });
    form.append(...parts, el("div", { class: "modal-actions" }, cancel, save));
    dialog.append(el("h2", { id: "edit-title", text: "행동 수정" }), form);

    cancel.addEventListener("click", () => dialog.close());
    dialog.addEventListener("close", () => dialog.remove());

    form.addEventListener("submit", async (event) => {
      event.preventDefault();
      clearFormErrors(form);
      const newTitle = title.value.trim();
      if (!newTitle) return setFieldError(form, "title", "제목을 입력해 주세요.");
      if (!isValidIsoDate(date.value)) return setFieldError(form, "performedDate", "올바른 날짜를 입력해 주세요.");

      // 바뀐 필드만 보낸다. 안 바꾼 필드까지 보내면 서버가 불필요한 규칙(예: 부모 상태)을 다시 검사한다.
      const body = { version: a.version };
      if (newTitle !== a.title) body.title = newTitle;
      if (date.value !== a.performedDate) body.performedDate = date.value;
      const newSummary = summary.value.trim();
      if (newSummary !== (a.resultSummary ?? "")) body.resultSummary = newSummary === "" ? null : newSummary;
      if (weeklySelect) {
        const weeklyChanged = !currentWeekly || Number(weeklySelect.value) !== currentWeekly.id;
        if (weeklyChanged) body.weeklyGoalId = Number(weeklySelect.value);
        const target = known(Number(weeklySelect.value));
        const nowVisible = !contributionField.hidden;
        const value = contribution.value === "" ? null : Number(contribution.value);
        if (target && target.measurementType !== "NUMERIC") {
          // 새 목표가 체크형이면 같은 요청에서 기여량을 null로 보내야 한다(§4.9).
          if (weeklyChanged && a.contributionValue !== null) body.contributionValue = null;
        } else if (nowVisible && value !== (a.contributionValue ?? null)) {
          body.contributionValue = value;
        }
      }
      if (Object.keys(body).length === 1) {
        dialog.close();
        return;
      }

      setBusy(save, true, "저장 중…");
      try {
        await api.patch(`/api/actions/${a.id}`, { body });
        dialog.close();
        toast("행동을 수정했어요.");
        await reloadProgress();
      } catch (error) {
        setBusy(save, false);
        handleApiError(error, {
          form,
          // 다른 곳에서 먼저 고쳐진 경우: 창을 닫고 최신 목록을 다시 불러온다(API §2.4).
          onVersionConflict: () => {
            dialog.close();
            loadActions();
          },
        });
      }
    });

    document.body.append(dialog);
    dialog.showModal();
    title.focus();
  }

  init().catch((error) => {
    if (!alive()) return;
    container.innerHTML = `<h1 class="page-title">대시보드</h1><p class="form-error" role="alert"></p>`;
    container.querySelector(".form-error").textContent = error.message || "대시보드를 불러오지 못했어요.";
    handleApiError(error);
  });

  return () => quad?.destroy();
}
