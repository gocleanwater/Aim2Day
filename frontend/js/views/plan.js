// 월간·주간 목표 설정 (#/plan). 이번 달 월간 목표와 이번 주 주간 목표를 만들고 고치고 지운다.
//   GET /api/goals/yearly, /api/goals/monthly, /api/goals/weekly, /api/goals/weekly/previous-summary, /api/settings
//   쓰기: POST·PATCH·DELETE /api/goals/monthly|weekly, 수치형 주간 목표의 직접 수치 기록 POST·DELETE .../numeric-records
// 대상 월·주는 서버가 계정 시간대 기준 "이번 달·이번 주"로 정한다(받지 않는다. 결정 5, GOAL-014).
import { api } from "../api.js";
import {
  clearFormErrors,
  el,
  formField,
  formatDate,
  handleApiError,
  renderLoading,
  setBusy,
  setFieldError,
  toast,
} from "../ui.js";
import { progressView, suffixOf } from "./dashboardSections.js";
import { DEFAULT_COLOR, STATUS_LABELS, deleteGoalFlow, normalizeColor } from "./goalCommon.js";

// 설명 입력 textarea의 인라인 스타일(공용 CSS에 textarea 규칙이 없어 이 화면에서 직접 지정한다).
const textareaStyle = "width:100%;border:1px solid #dfe5eb;border-radius:12px;padding:10px 14px;font:inherit";

// 화면 진입점. 데이터 흐름: load()가 목표·설정을 병렬로 받아 state에 담고 draw()가 통째로 다시 그린다.
// 쓰기(생성·수정·삭제·수치 기록)는 성공하면 항상 load()로 서버 값을 다시 받는다(진행률·가중치 계산은 서버가 한다).
// "이번 달·이번 주"는 클라이언트가 보내지 않고 서버가 계정 시간대로 정하므로, 화면은 settings.currentMonth·currentWeek만 표시한다.
export function render(container, route) {
  renderLoading(container);
  const alive = () => route.isActive();
  // weekParents: 이번 주와 기간이 겹치는 월간 목표(월 경계 주는 두 달의 목표). 주간 목표를 새로 만들 때의 부모 후보다.
  const state = { yearly: [], monthly: [], weekly: [], weekParents: [], previous: null, settings: null };
  const openRecords = new Set(); // 수치 기록 패널을 펼친 주간 목표 id(다시 그려도 유지)
  const records = new Map(); // 주간 목표 id → 기록 목록

  /**
   * 기간(startDate~endDate)과 겹치는 월간 목표들. 시작일의 달과 종료일의 달을 각각 읽어 합친다(중복 id 제거).
   * 월·연도 경계 주는 두 달에 걸쳐 있어, 이번 달 목록만으로는 지난달(또는 다음 달) 부모를 고를 수 없다(API §4.6, GOAL-006).
   * 현재 달은 이미 읽어 둔 state.monthly를 재사용해 요청을 줄인다.
   */
  async function monthlyOverlapping(startDate, endDate) {
    const result = new Map();
    for (const month of new Set([startDate.slice(0, 7), endDate.slice(0, 7)])) {
      const list = month === state.settings.currentMonth.month ? state.monthly : await api.get("/api/goals/monthly", { query: { month } });
      for (const m of list) result.set(m.id, m);
    }
    return [...result.values()];
  }

  /** 월간 목표 선택지 문구. 여러 달이 섞여 있으면 어느 달인지 구분하도록 달을 붙인다. */
  function monthlyLabel(m, month, mixed) {
    return `${m.yearlyGoal?.title ?? ""} › ${m.title}${mixed && month ? ` (${Number(month.slice(5, 7))}월)` : ""}`;
  }

  // 필요한 목록을 병렬로 받는다. 수치 기록 패널을 펼쳐 둔 주간 목표는 기록도 함께 새로 받아 다시 그려도 열린 상태가 유지된다.
  async function load() {
    try {
      const [yearly, monthly, weekly, previous, settings] = await Promise.all([
        api.get("/api/goals/yearly"),
        api.get("/api/goals/monthly"),
        api.get("/api/goals/weekly"),
        api.get("/api/goals/weekly/previous-summary"),
        api.get("/api/settings"),
      ]);
      if (!alive()) return;
      Object.assign(state, { yearly: yearly.items ?? [], monthly, weekly, previous, settings });
      state.weekParents = await monthlyOverlapping(settings.currentWeek.startDate, settings.currentWeek.endDate);
      if (!alive()) return;
      // 펼쳐 둔 수치 기록 패널의 내용도 함께 새로 받는다.
      await Promise.all(
        [...openRecords].filter((id) => state.weekly.some((w) => w.id === id)).map(async (id) => records.set(id, await api.get(`/api/goals/weekly/${id}/numeric-records`))),
      );
    } catch (error) {
      if (!alive()) return;
      container.replaceChildren(el("h1", { class: "page-title", text: "월간·주간 목표 설정" }), el("p", { class: "form-error", role: "alert", text: error.message }));
      handleApiError(error);
      return;
    }
    if (!alive()) return;
    draw();
  }

  // 화면 전체: 월간 생성 폼, 주간 생성 폼, 직전 주 요약, 설정한 목표 목록.
  function draw() {
    container.replaceChildren(
      el("p", { class: "note" }, el("a", { href: "#/dashboard", text: "대시보드" }), " > 월간·주간 목표 설정"),
      el("h1", { class: "page-title", text: "월간·주간 목표 설정" }),
      el("p", { class: "lead", text: "큰 목표에서 이번 달의 결과를 정하고, 그 결과를 이번 주 목표로 구체화해요." }),
      el("div", { class: "two-col" }, monthlyForm(), weeklyForm()),
      previousSummary(),
      goalList(),
    );
  }

  // ---------- 월간 생성 ----------
  // 월간 목표 생성 폼. 대상 월은 서버가 이번 달로 정하므로 보내지 않는다. 색은 체크했을 때만 보내고 끄면 null(상위 연간 목표 색을 따른다).
  function monthlyForm() {
    const parent = el("select", { id: "monthly-parent", name: "yearlyGoalId", testid: "monthly-parent" }, [
      el("option", { value: "", text: "연간 목표 선택" }),
      ...state.yearly.map((g) => el("option", { value: String(g.id), text: g.title })),
    ]);
    if (state.yearly.length === 1) parent.value = String(state.yearly[0].id);
    const title = el("input", { id: "monthly-title", name: "title", type: "text", maxlength: 100, testid: "monthly-title", placeholder: "예: 알고리즘 문제 40개 풀기" });
    const description = el("input", { id: "monthly-description", name: "description", type: "text", maxlength: 500, testid: "monthly-description" });
    const useColor = el("input", { id: "monthly-use-color", type: "checkbox", testid: "monthly-use-color" });
    const color = el("input", { id: "monthly-color", name: "color", type: "color", value: DEFAULT_COLOR.toLowerCase(), disabled: true, testid: "monthly-color", style: "min-height:46px;padding:4px;max-width:90px" });
    useColor.addEventListener("change", () => (color.disabled = !useColor.checked));
    const submit = el("button", { type: "submit", class: "btn", text: "월간 목표 저장", testid: "monthly-submit" });
    const form = el(
      "form",
      { class: "card", novalidate: true, testid: "monthly-form", "aria-label": "이번 달 목표 만들기" },
      el("h2", { style: "margin:0 0 12px;font-size:17px", text: "이번 달 목표 만들기" }),
      el("p", { class: "form-error", "data-form-error": true, role: "alert", hidden: true }),
      state.yearly.length === 0 ? el("p", { class: "hint-warn" }, "연간 목표가 없어요. ", el("a", { href: "#/goals", text: "연간 목표 만들기" })) : null,
      formField("연결할 올해 목표", parent, "yearlyGoalId"),
      el("p", { class: "note", testid: "monthly-period", text: `기간: 이번 달 ${state.settings.currentMonth.month.replace("-", ".")} (자동)` }),
      formField("이번 달에 이루고 싶은 결과", title, "title"),
      formField("설명 (선택)", description, "description"),
      el("div", { class: "field" }, el("label", { for: "monthly-use-color" }, useColor, " 색 직접 지정 (끄면 올해 목표의 색을 따라가요)"), color),
      submit,
    );
    form.addEventListener("submit", async (event) => {
      event.preventDefault();
      clearFormErrors(form);
      let bad = false;
      if (!parent.value) { setFieldError(form, "yearlyGoalId", "연결할 올해 목표를 선택해 주세요."); bad = true; }
      if (!title.value.trim()) { setFieldError(form, "title", "이번 달 결과를 입력해 주세요."); bad = true; }
      if (bad) return;
      setBusy(submit, true, "저장 중…");
      try {
        await api.post("/api/goals/monthly", {
          body: {
            yearlyGoalId: Number(parent.value),
            title: title.value.trim(),
            description: description.value.trim() || null,
            color: useColor.checked ? normalizeColor(color.value) : null,
          },
        });
        toast("월간 목표를 만들었어요.");
        await load();
      } catch (error) {
        setBusy(submit, false);
        handleApiError(error, { form });
      }
    });
    return form;
  }

  // ---------- 주간 생성 ----------
  // 주간 목표 생성 폼. 부모는 이번 주와 기간이 겹치는 월간 목표(weekParents)에서 고른다. 수치형이면 목표 수치를 함께 받는다.
  function weeklyForm() {
    const mixed = new Set(state.weekParents.map((m) => m.month)).size > 1;
    const parent = el("select", { id: "weekly-parent", name: "monthlyGoalId", testid: "weekly-parent" }, [
      el("option", { value: "", text: mixed ? "월간 목표 선택" : "이번 달 목표 선택" }),
      ...state.weekParents.map((m) => el("option", { value: String(m.id), text: monthlyLabel(m, m.month, mixed) })),
    ]);
    if (state.weekParents.length === 1) parent.value = String(state.weekParents[0].id);
    const title = el("input", { id: "weekly-title", name: "title", type: "text", maxlength: 100, testid: "weekly-title", placeholder: "예: 알고리즘 문제 10개 풀기" });
    const checklist = el("input", { type: "radio", name: "measurementType", value: "CHECKLIST", checked: true, testid: "weekly-type-checklist" });
    const numeric = el("input", { type: "radio", name: "measurementType", value: "NUMERIC", testid: "weekly-type-numeric" });
    const target = el("input", { id: "weekly-target", name: "targetValue", type: "number", min: "0", step: "any", inputmode: "decimal", testid: "weekly-target" });
    const targetField = formField("목표 수치", target, "targetValue", "수치형일 때 이번 주에 채울 양이에요. 0보다 커야 해요.");
    targetField.hidden = true;
    const weight = el("input", { id: "weekly-weight", name: "weight", type: "number", min: "0", max: "999.99", step: "any", value: "1", testid: "weekly-weight" });
    const sync = () => (targetField.hidden = !numeric.checked);
    checklist.addEventListener("change", sync);
    numeric.addEventListener("change", sync);
    const submit = el("button", { type: "submit", class: "btn", text: "주간 목표 저장", testid: "weekly-submit" });
    const form = el(
      "form",
      { class: "card", novalidate: true, testid: "weekly-form", "aria-label": "이번 주 목표 만들기" },
      el("h2", { style: "margin:0 0 12px;font-size:17px", text: "이번 주 목표 만들기" }),
      el("p", { class: "form-error", "data-form-error": true, role: "alert", hidden: true }),
      state.weekParents.length === 0 ? el("p", { class: "hint-warn", text: "이번 달 목표를 먼저 만들어야 주간 목표를 연결할 수 있어요." }) : null,
      formField("연결할 이번 달 목표", parent, "monthlyGoalId", "주간 목표는 한 월간 목표에만 연결돼요."),
      el("p", { class: "note", testid: "weekly-period", text: `기간: 이번 주 ${formatDate(state.settings.currentWeek.startDate)} – ${formatDate(state.settings.currentWeek.endDate)} (자동)` }),
      formField("이번 주에 이루고 싶은 결과", title, "title"),
      el("fieldset", {}, el("legend", { text: "측정 방식" }), el("div", { class: "radio-grid" }, el("label", {}, checklist, "체크리스트"), el("label", {}, numeric, "수치형")), el("p", { class: "field-error", "data-error-for": "measurementType" })),
      targetField,
      formField("가중치", weight, "weight", "월간 진행률에 반영되는 비중이에요(기본 1)."),
      submit,
    );
    form.addEventListener("submit", async (event) => {
      event.preventDefault();
      clearFormErrors(form);
      let bad = false;
      if (!parent.value) { setFieldError(form, "monthlyGoalId", "연결할 이번 달 목표를 선택해 주세요."); bad = true; }
      if (!title.value.trim()) { setFieldError(form, "title", "이번 주 결과를 입력해 주세요."); bad = true; }
      if (numeric.checked && !(Number(target.value) > 0)) { setFieldError(form, "targetValue", "목표 수치는 0보다 커야 해요."); bad = true; }
      if (bad) return;
      const body = {
        monthlyGoalId: Number(parent.value),
        title: title.value.trim(),
        description: null,
        color: null,
        measurementType: numeric.checked ? "NUMERIC" : "CHECKLIST",
        weight: Number(weight.value) || 1,
      };
      if (numeric.checked) body.targetValue = Number(target.value);
      setBusy(submit, true, "저장 중…");
      try {
        await api.post("/api/goals/weekly", { body });
        toast("주간 목표를 만들었어요.");
        await load();
      } catch (error) {
        setBusy(submit, false);
        handleApiError(error, { form });
      }
    });
    return form;
  }

  // ---------- 직전 주 요약 ----------
  // 직전 주 요약(GET /api/goals/weekly/previous-summary). 지난주 결과를 보고 이번 주 목표를 정하도록 돕는 읽기 전용 영역이다.
  function previousSummary() {
    const p = state.previous;
    const goals = p?.weeklyGoals ?? [];
    return el(
      "section",
      { class: "card", testid: "previous-summary", "aria-labelledby": "prev-heading" },
      el("h2", { id: "prev-heading", style: "margin:0 0 4px;font-size:17px", text: "직전 주 실제 진행" }),
      p ? el("p", { class: "note", text: `${formatDate(p.startDate)} – ${formatDate(p.endDate)} · 이번 주 계획을 세울 때 참고해요.` }) : null,
      goals.length === 0
        ? el("p", { class: "note", testid: "previous-empty", text: "기록 없음" })
        : goals.map((g) =>
            el(
              "div",
              { class: "plan-card", testid: `previous-${g.id}` },
              el("div", { class: "plan-top", text: g.title }),
              el("p", { class: "action-meta", text: [g.path?.yearlyGoal?.title, g.path?.monthlyGoal?.title].filter(Boolean).join(" › ") }),
              progressView(g.progress, ""),
              el("p", { class: "action-meta", text: `완료한 일: ${g.completedActions.length ? g.completedActions.map((a) => a.title).join(", ") : "없음"}` }),
              el("p", { class: "action-meta", text: `남은 일: ${g.remainingActions.length ? g.remainingActions.map((a) => a.title).join(", ") : "없음"}` }),
            ),
          ),
    );
  }

  // ---------- 설정한 목표 목록 ----------
  // 이번 달 월간 목표와 그 아래 이번 주 주간 목표를 묶어 보여 준다. 부모 월간 목표가 목록에 없는 주간 목표(월 경계 주 등)는 따로 모아 마지막에 둔다.
  function goalList() {
    const weeklyByMonthly = new Map();
    for (const w of state.weekly) {
      const key = w.path?.monthlyGoal?.id;
      if (!weeklyByMonthly.has(key)) weeklyByMonthly.set(key, []);
      weeklyByMonthly.get(key).push(w);
    }
    const known = new Set(state.monthly.map((m) => m.id));
    const orphan = state.weekly.filter((w) => !known.has(w.path?.monthlyGoal?.id));
    return el(
      "section",
      { class: "card", testid: "goal-list", "aria-labelledby": "list-heading" },
      el("div", { class: "dash-head" }, el("h2", { id: "list-heading", style: "margin:0;font-size:17px", text: "설정한 목표" }), el("a", { href: "#/goals/trash", text: "삭제된 목표 보기" })),
      state.monthly.length === 0 && orphan.length === 0 ? el("p", { class: "note", testid: "plan-empty", text: "아직 이번 달·이번 주 목표가 없어요." }) : null,
      state.monthly.map((m) => monthlyRow(m, weeklyByMonthly.get(m.id) ?? [])),
      orphan.length ? el("h3", { text: "다른 달 목표에 연결된 이번 주 목표" }) : null,
      orphan.map((w) => weeklyRow(w)),
    );
  }

  // 월간 목표 카드(수정·삭제 버튼, 진행률, 소속 주간 목표).
  function monthlyRow(m, weeklies) {
    return el(
      "div",
      { class: "plan-card", testid: `monthly-row-${m.id}` },
      el("p", { class: "action-meta", text: m.yearlyGoal?.title ?? "" }),
      el(
        "div",
        { class: "plan-top" },
        el("span", { text: `${m.title}${suffixOf(m)}` }),
        el(
          "span",
          { class: "action-buttons" },
          el("button", { type: "button", class: "link-button", text: "수정", "aria-label": `${m.title} 수정`, testid: `monthly-edit-${m.id}`, onclick: () => openEdit("monthly", m) }),
          el("button", { type: "button", class: "link-button", text: "삭제", "aria-label": `${m.title} 삭제`, testid: `monthly-delete-${m.id}`, onclick: () => remove("monthly", m) }),
        ),
      ),
      progressView(m.progress, `주간 목표 ${m.weeklyGoalCount ?? weeklies.length}개`),
      weeklies.map((w) => weeklyRow(w)),
    );
  }

  // 주간 목표 카드. 수치형이면 현재/목표 값과 [수치 기록] 패널 토글이 있고, 펼칠 때 기록 목록을 받아 온다.
  function weeklyRow(w) {
    const numeric = w.measurementType === "NUMERIC";
    const toggle = el("button", {
      type: "button",
      class: "link-button",
      text: openRecords.has(w.id) ? "수치 기록 닫기" : "수치 기록",
      "aria-expanded": openRecords.has(w.id) ? "true" : "false",
      testid: `records-toggle-${w.id}`,
      onclick: async () => {
        if (openRecords.has(w.id)) openRecords.delete(w.id);
        else {
          try {
            const list = await api.get(`/api/goals/weekly/${w.id}/numeric-records`);
            if (!alive()) return; // 응답을 기다리는 사이 다른 화면으로 갔다면 #view를 다시 그리지 않는다
            records.set(w.id, list);
            openRecords.add(w.id);
          } catch (error) {
            return handleApiError(error);
          }
        }
        draw();
      },
    });
    return el(
      "div",
      { style: "margin:10px 0 0 16px", testid: `weekly-row-${w.id}` },
      el(
        "div",
        { class: "plan-top" },
        el("span", { text: `${w.title}${suffixOf(w)}` }),
        el(
          "span",
          { class: "action-buttons" },
          numeric ? toggle : null,
          el("button", { type: "button", class: "link-button", text: "수정", "aria-label": `${w.title} 수정`, testid: `weekly-edit-${w.id}`, onclick: () => openEdit("weekly", w) }),
          el("button", { type: "button", class: "link-button", text: "삭제", "aria-label": `${w.title} 삭제`, testid: `weekly-delete-${w.id}`, onclick: () => remove("weekly", w) }),
        ),
      ),
      el("p", { class: "action-meta", text: numeric ? `수치형 · ${w.currentValue} / ${w.targetValue}${w.isTargetReached ? " · 목표 달성" : ""} · 가중치 ${w.weight}` : `체크리스트 · 행동 ${w.actionCounts?.completed ?? 0} / ${w.actionCounts?.total ?? 0} 완료 · 가중치 ${w.weight}` }),
      progressView(w.progress, ""),
      numeric && openRecords.has(w.id) ? recordsPanel(w) : null,
    );
  }

  // ---------- 수치 기록 ----------
  // 수치형 주간 목표의 직접 수치 기록 패널(목록, 추가 POST, 삭제 DELETE).
  function recordsPanel(w) {
    const list = records.get(w.id) ?? [];
    const amount = el("input", { id: `record-amount-${w.id}`, name: "amount", type: "number", min: "0", step: "any", inputmode: "decimal", testid: `record-amount-${w.id}` });
    const memo = el("input", { id: `record-memo-${w.id}`, name: "memo", type: "text", maxlength: 255, testid: `record-memo-${w.id}` });
    const add = el("button", { type: "submit", class: "btn btn-secondary btn-small", text: "기록 추가", testid: `record-add-${w.id}` });
    const form = el(
      "form",
      { novalidate: true, "aria-label": `${w.title} 수치 기록 추가` },
      el("p", { class: "form-error", "data-form-error": true, role: "alert", hidden: true }),
      el("p", { class: "hint-warn", text: "행동을 완료할 때 이미 더해진 수치는 다시 입력하지 마세요(중복으로 합산돼요)." }),
      el("div", { class: "add-form" }, formField("이번에 추가한 양", amount, "amount"), formField("메모 (선택)", memo, "memo")),
      add,
    );
    form.addEventListener("submit", async (event) => {
      event.preventDefault();
      clearFormErrors(form);
      if (!(Number(amount.value) > 0)) return setFieldError(form, "amount", "0보다 큰 값을 입력해 주세요.");
      setBusy(add, true, "추가 중…");
      try {
        await api.post(`/api/goals/weekly/${w.id}/numeric-records`, { body: { amount: Number(amount.value), memo: memo.value.trim() || null } });
        toast("수치를 기록했어요.");
        await load();
      } catch (error) {
        setBusy(add, false);
        handleApiError(error, { form });
      }
    });
    return el(
      "div",
      { class: "onb-row", testid: `records-${w.id}` },
      list.length === 0
        ? el("p", { class: "note", text: "아직 수치 기록이 없어요." })
        : el(
            "ul",
            { class: "list-plain" },
            list.map((r) =>
              el(
                "li",
                { class: "log-row", testid: `record-${r.id}` },
                `${r.amount} · ${r.source === "ACTION_COMPLETION" ? `행동 완료${r.action?.title ? `: ${r.action.title}` : ""}` : "직접 입력"}${r.memo ? ` · ${r.memo}` : ""} `,
                // 행동 완료로 생긴 기록은 완료 취소로만 지울 수 있다(RECORD_NOT_DELETABLE). 직접 입력만 삭제 버튼을 둔다.
                r.source === "DIRECT_INPUT"
                  ? el("button", { type: "button", class: "link-button", text: "삭제", "aria-label": "수치 기록 삭제", testid: `record-delete-${r.id}`, onclick: () => deleteRecord(w, r) })
                  : null,
              ),
            ),
          ),
      form,
    );
  }

  // 수치 기록 한 건 삭제. 진행률이 바뀌므로 전체를 다시 읽는다.
  async function deleteRecord(w, r) {
    try {
      await api.delete(`/api/goals/weekly/${w.id}/numeric-records/${r.id}`);
      toast("기록을 삭제했어요.");
      await load();
    } catch (error) {
      handleApiError(error, { onVersionConflict: load });
    }
  }

  // ---------- 삭제 ----------
  // 월간·주간 목표 삭제(level은 "monthly"|"weekly"). 하위 항목 확인 흐름은 deleteGoalFlow가 맡는다.
  async function remove(level, goal) {
    try {
      const done = await deleteGoalFlow(level, goal.id, goal.title, alive);
      if (done && alive()) await load();
    } catch (error) {
      if (alive()) handleApiError(error, { onVersionConflict: load });
    }
  }

  // ---------- 수정 ----------
  // 수정 모달(월간·주간 공용). 열 때 값(g)과 비교해 바뀐 필드만 PATCH 본문에 담고(version은 항상 포함, 동시 수정 방지 API §2.4),
  // 바뀐 것이 없으면 요청 없이 닫는다. 주간 목표는 부모 월간 목표 변경과 가중치·목표 수치도 고칠 수 있고, 측정 방식은 바꿀 수 없다.
  async function openEdit(level, g) {
    // 주간 목표의 부모 후보: 그 주와 기간이 겹치는 월간 목표 전부 + 지금 연결된 부모.
    // 월 경계 주의 부모가 지난달 목표인 경우, 이번 달 목록만 쓰면 현재 부모가 선택지에 없어 값이 비어 버린다.
    let parentOptions = [];
    if (level === "weekly") {
      let overlapping = [];
      try {
        overlapping = await monthlyOverlapping(g.startDate, g.endDate);
      } catch (error) {
        if (!alive()) return;
        handleApiError(error); // 후보를 못 읽어도 현재 부모는 유지한 채 다른 항목은 고칠 수 있게 계속 진행한다
      }
      if (!alive()) return;
      parentOptions = overlapping.map((m) => ({ id: m.id, month: m.month, yearly: m.yearlyGoal?.title ?? "", title: m.title }));
      const current = g.path?.monthlyGoal;
      if (current && !parentOptions.some((o) => o.id === current.id)) {
        parentOptions.unshift({ id: current.id, month: current.month, yearly: g.path?.yearlyGoal?.title ?? "", title: current.title });
      }
    }
    const dialog = el("dialog", { class: "modal", "aria-labelledby": "plan-edit-heading", testid: `${level}-edit-dialog` });
    const form = el("form", { novalidate: true });
    const title = el("input", { id: "plan-edit-title", name: "title", type: "text", maxlength: 100, testid: `${level}-edit-title` });
    title.value = g.title;
    const description = el("textarea", { id: "plan-edit-description", name: "description", rows: "2", maxlength: 500, testid: `${level}-edit-description`, style: textareaStyle });
    description.value = g.description ?? "";
    const status = el("select", { id: "plan-edit-status", name: "status", testid: `${level}-edit-status` });
    for (const [value, label] of Object.entries(STATUS_LABELS)) status.append(el("option", { value, text: label }));
    status.value = g.status;
    const fields = [formField("제목", title, "title"), formField("설명", description, "description"), formField("상태", status, "status")];

    let target = null;
    let weight = null;
    let parent = null;
    // 월간·주간 모두 색을 직접 지정하거나(체크) 끄면 상위 목표의 색을 따라간다(color=null, FN-GOAL-02).
    const useColor = el("input", { id: "plan-edit-use-color", type: "checkbox", testid: `${level}-edit-use-color` });
    useColor.checked = Boolean(g.color);
    const color = el("input", { id: "plan-edit-color", name: "color", type: "color", testid: `${level}-edit-color`, style: "min-height:46px;padding:4px;max-width:90px" });
    color.value = (g.color || g.displayColor || DEFAULT_COLOR).toLowerCase();
    color.disabled = !useColor.checked;
    useColor.addEventListener("change", () => (color.disabled = !useColor.checked));
    fields.push(
      el("div", { class: "field" }, el("label", { for: "plan-edit-use-color" }, useColor, level === "monthly" ? " 색 직접 지정 (끄면 올해 목표의 색을 따라가요)" : " 색 직접 지정 (끄면 월간 목표의 색을 따라가요)"), color),
    );
    if (level === "weekly") {
      if (g.measurementType === "NUMERIC") {
        target = el("input", { id: "plan-edit-target", name: "targetValue", type: "number", min: "0", step: "any", testid: "weekly-edit-target" });
        target.value = g.targetValue ?? "";
        fields.push(formField("목표 수치", target, "targetValue"));
      }
      weight = el("input", { id: "plan-edit-weight", name: "weight", type: "number", min: "0", max: "999.99", step: "any", testid: "weekly-edit-weight" });
      weight.value = g.weight ?? 1;
      fields.push(formField("가중치", weight, "weight"));
      const mixed = new Set(parentOptions.map((o) => o.month)).size > 1;
      parent = el("select", { id: "plan-edit-parent", name: "monthlyGoalId", testid: "weekly-edit-parent" }, parentOptions.map((o) => el("option", { value: String(o.id), text: monthlyLabel({ yearlyGoal: { title: o.yearly }, title: o.title }, o.month, mixed) })));
      parent.value = String(g.path?.monthlyGoal?.id ?? "");
      fields.push(formField("연결된 월간 목표", parent, "monthlyGoalId", "주간 목표는 이 주와 기간이 겹치는 월간 목표로만 옮길 수 있어요."));
      fields.push(el("p", { class: "note", text: `측정 방식: ${g.measurementType === "NUMERIC" ? "수치형" : "체크리스트"} (만든 뒤에는 바꿀 수 없어요)` }));
    }

    const save = el("button", { type: "submit", class: "btn", text: "저장", testid: `${level}-edit-save` });
    form.append(
      el("p", { class: "form-error", "data-form-error": true, role: "alert", hidden: true }),
      ...fields,
      el("div", { class: "modal-actions" }, el("button", { type: "button", class: "btn btn-outline", text: "취소", onclick: () => dialog.close() }), save),
    );
    dialog.append(el("h2", { id: "plan-edit-heading", text: level === "monthly" ? "월간 목표 수정" : "주간 목표 수정" }), form);
    dialog.addEventListener("close", () => dialog.remove());

    form.addEventListener("submit", async (event) => {
      event.preventDefault();
      clearFormErrors(form);
      const text = title.value.trim();
      if (!text) return setFieldError(form, "title", "제목을 입력해 주세요.");
      // 바뀐 필드만 보낸다(안 바꾼 필드까지 보내면 서버가 불필요한 규칙을 다시 검사한다).
      const body = { version: g.version };
      if (text !== g.title) body.title = text;
      if ((description.value.trim() || null) !== (g.description ?? null)) body.description = description.value.trim() || null;
      if (status.value !== g.status) body.status = status.value;
      // 색은 바뀐 경우에만 보낸다. 체크를 끄면 null을 보내 상위 목표의 색을 따라가게 한다.
      const newColor = useColor.checked ? normalizeColor(color.value) : null;
      if (newColor !== (g.color ? g.color.toUpperCase() : null)) body.color = newColor;
      if (level === "weekly") {
        if (target && Number(target.value) !== Number(g.targetValue)) {
          if (!(Number(target.value) > 0)) return setFieldError(form, "targetValue", "목표 수치는 0보다 커야 해요.");
          body.targetValue = Number(target.value);
        }
        if (Number(weight.value) !== Number(g.weight)) body.weight = Number(weight.value);
        // 부모는 사용자가 실제로 바꿨을 때만 보낸다(값이 비었을 때 0을 보내지 않는다).
        if (parent.value && Number(parent.value) !== g.path?.monthlyGoal?.id) body.monthlyGoalId = Number(parent.value);
      }
      if (Object.keys(body).length === 1) return dialog.close();
      setBusy(save, true, "저장 중…");
      try {
        await api.patch(`/api/goals/${level}/${g.id}`, { body });
        dialog.close();
        toast("수정했어요.");
        await load();
      } catch (error) {
        setBusy(save, false);
        // 409: 다른 곳에서 먼저 바뀌었다. 창을 닫고 최신 값을 다시 불러온다.
        handleApiError(error, { form, onVersionConflict: () => { dialog.close(); load(); } });
      }
    });
    document.body.append(dialog);
    dialog.showModal();
    title.focus();
  }

  load();
}
