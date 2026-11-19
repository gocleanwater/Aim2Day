// 연간 목표 상세 (#/goals/{id}).
//   GET /api/goals/yearly/{id}            목표 + 하위 월간·주간 트리
//   GET /api/goals/yearly/{id}/criteria   달성 기준
//   GET /api/goals/progress-logs          최근 진행 기록(이 목표 것만 골라 보여 준다)
// 쓰기: PATCH 기본 정보(version), PUT criteria(goalVersion), POST criteria/confirm, PUT criteria/{id}/achievement, DELETE.
//
// 달성 기준의 화면 상태(mode):
//   edit     확정 전 초안 편집, 또는 확정 후 개정. 배점 합계를 실시간으로 보여 준다(확정·개정은 합계가 정확히 100이어야 한다).
//   view     확정된 기준을 읽고 항목별 달성을 토글한다.
//   confirm  확정 직전: 이미 달성한 기준을 골라 최초 기준선을 정한다.
import { api } from "../api.js";
import { navigate } from "../navigation.js";
import {
  clearFormErrors,
  el,
  formField,
  formatDate,
  handleApiError,
  renderLoading,
  setBusy,
  setFieldError,
  setFormError,
  toast,
} from "../ui.js";
import { progressView, suffixOf } from "./dashboardSections.js";
import { DEFAULT_COLOR, STATUS_LABELS, deleteGoalFlow, formatInstantDate, loadAccountTimeZone, normalizeColor } from "./goalCommon.js";

const MAX_CRITERIA = 20;
const TARGET_LABEL = { WEEKLY_GOAL: "주간", MONTHLY_GOAL: "월간", YEARLY_GOAL: "연간" };
const CHANGE_LABEL = { CRITERIA_REVISION: "기준 개정", PROGRESS_UPDATE: "진행 변경" };

export function render(container, route) {
  renderLoading(container);
  const alive = () => route.isActive();
  const goalId = Number(route.params.id);

  const state = { detail: null, criteria: null, logs: [] };
  let mode = "view";
  let draftItems = []; // 편집 중 기준 [{id?, text, weight}]
  let memo = "";
  let baseline = new Set(); // 확정 때 이미 달성한 기준 id
  const ui = {};

  // ---------- 불러오기 ----------
  async function loadAll() {
    try {
      await loadAccountTimeZone(); // 확정·달성 날짜를 계정 시간대로 표시하려고 먼저 읽는다(실패해도 진행)
      const [detail, criteria, logs] = await Promise.all([
        api.get(`/api/goals/yearly/${goalId}`),
        api.get(`/api/goals/yearly/${goalId}/criteria`),
        api.get("/api/goals/progress-logs", { query: { limit: 50 } }),
      ]);
      if (!alive()) return;
      state.detail = detail;
      state.criteria = criteria;
      state.logs = logs;
    } catch (error) {
      if (!alive()) return;
      container.replaceChildren(
        el("h1", { class: "page-title", text: "연간 목표" }),
        el("p", { class: "form-error", role: "alert", text: error.message }),
        el("a", { href: "#/goals", text: "연간 목표 목록으로" }),
      );
      handleApiError(error);
      return;
    }
    mode = state.criteria.state === "CONFIRMED" ? "view" : "edit";
    draftItems = state.criteria.items.map((i) => ({ id: i.id, text: i.text, weight: i.weight }));
    if (mode === "edit" && draftItems.length === 0) draftItems.push({ text: "", weight: "" });
    memo = "";
    baseline = new Set();
    draw();
  }

  /** 기준·기본 정보·진행 기록이 함께 바뀌는 쓰기 뒤에 쓴다(goal.version이 올라가므로 전부 새로 읽는다). */
  const reload = () => loadAll();

  async function reloadCriteriaOnly() {
    try {
      const [detail, criteria, logs] = await Promise.all([
        api.get(`/api/goals/yearly/${goalId}`),
        api.get(`/api/goals/yearly/${goalId}/criteria`),
        api.get("/api/goals/progress-logs", { query: { limit: 50 } }),
      ]);
      if (!alive()) return;
      state.detail = detail;
      state.criteria = criteria;
      state.logs = logs;
      drawHeader();
      drawCriteria();
      drawTree();
      drawLogs();
    } catch (error) {
      handleApiError(error);
    }
  }

  // ---------- 골격 ----------
  function draw() {
    ui.header = el("section", { class: "card", testid: "goal-header" });
    ui.criteria = el("section", { class: "card", testid: "criteria-card", "aria-labelledby": "criteria-heading" });
    ui.tree = el("section", { class: "card", testid: "goal-tree", "aria-labelledby": "tree-heading" });
    ui.logs = el("section", { class: "card", testid: "goal-logs", "aria-labelledby": "logs-heading" });
    container.replaceChildren(
      el("p", { class: "note" }, el("a", { href: "#/goals", text: "연간 목표" }), " > ", el("span", { text: state.detail.goal.title })),
      el("div", { class: "dash" }, ui.header, ui.criteria, ui.tree, ui.logs),
    );
    drawHeader();
    drawCriteria();
    drawTree();
    drawLogs();
  }

  // ---------- 기본 정보 ----------
  function drawHeader() {
    const g = state.detail.goal;
    const title = el("input", { id: "goal-title", name: "title", type: "text", maxlength: 100, testid: "goal-title" });
    title.value = g.title;
    const description = el("textarea", {
      id: "goal-description",
      name: "description",
      rows: "2",
      maxlength: 500,
      testid: "goal-description",
      style: "width:100%;border:1px solid #dfe5eb;border-radius:12px;padding:10px 14px;font:inherit",
    });
    description.value = g.description ?? "";
    const color = el("input", { id: "goal-color", name: "color", type: "color", testid: "goal-color", style: "min-height:46px;padding:4px" });
    color.value = (g.color || DEFAULT_COLOR).toLowerCase();
    const status = el("select", { id: "goal-status", name: "status", testid: "goal-status" });
    for (const [value, label] of Object.entries(STATUS_LABELS)) status.append(el("option", { value, text: label }));
    status.value = g.status;
    const save = el("button", { type: "submit", class: "btn", text: "저장", testid: "goal-save" });

    const form = el(
      "form",
      { novalidate: true, "aria-label": "기본 정보" },
      el("p", { class: "form-error", "data-form-error": true, role: "alert", hidden: true }),
      formField("목표 이름", title, "title"),
      formField("설명", description, "description"),
      el("div", { class: "add-form" }, formField("표시 색상", color, "color"), formField("상태", status, "status")),
      el("p", { class: "note", text: `기간 ${g.targetYear}년` }),
      el(
        "div",
        { class: "btn-row" },
        save,
        el("button", { type: "button", class: "btn btn-outline", text: "삭제", testid: "goal-delete", onclick: removeGoal }),
      ),
    );
    form.addEventListener("submit", async (event) => {
      event.preventDefault();
      clearFormErrors(form);
      const text = title.value.trim();
      if (!text) return setFieldError(form, "title", "목표 이름을 입력해 주세요.");
      const body = { version: g.version };
      if (text !== g.title) body.title = text;
      if ((description.value.trim() || null) !== (g.description ?? null)) body.description = description.value.trim() || null;
      const newColor = normalizeColor(color.value);
      if (newColor !== (g.color || "").toUpperCase()) body.color = newColor;
      if (status.value !== g.status) body.status = status.value;
      if (Object.keys(body).length === 1) return toast("바뀐 내용이 없어요.");
      setBusy(save, true, "저장 중…");
      try {
        await api.patch(`/api/goals/yearly/${goalId}`, { body });
        toast("저장했어요.");
        await reload();
      } catch (error) {
        setBusy(save, false);
        // 409: 다른 곳에서 먼저 바뀌었다. 안내 후 최신 값으로 화면을 다시 채운다.
        handleApiError(error, { form, onVersionConflict: reload });
      }
    });

    ui.header.replaceChildren(
      el(
        "div",
        { class: "dash-head" },
        el("h1", { class: "page-title", style: "margin:0", text: g.title + suffixOf(g) }),
        el("span", { class: "badge", testid: "goal-status-badge", text: STATUS_LABELS[g.status] ?? g.status }),
      ),
      form,
    );
  }

  async function removeGoal() {
    const g = state.detail.goal;
    try {
      const done = await deleteGoalFlow("yearly", goalId, g.title, alive);
      if (done && alive()) navigate("#/goals"); // 삭제 확인 중 다른 화면으로 갔다면 그 화면을 덮어 이동하지 않는다
    } catch (error) {
      handleApiError(error, { onVersionConflict: reload });
    }
  }

  // ---------- 달성 기준 ----------
  const sumOf = (items) => items.reduce((sum, i) => sum + (Number.isFinite(Number(i.weight)) ? Number(i.weight) : 0), 0);

  function drawCriteria() {
    const c = state.criteria;
    const confirmed = c.state === "CONFIRMED";
    const parts = [el("h2", { id: "criteria-heading", style: "margin:0 0 8px;font-size:17px", text: "달성 기준" })];

    if (confirmed) {
      parts.push(
        el("p", { class: "note", testid: "criteria-summary", text: `달성률 ${c.achievementRate}% · 최초 기준선 ${c.initialBaselineRate}% · 확정 ${formatInstantDate(c.confirmedAt)}${c.lastRevisedAt ? ` · 마지막 개정 ${formatInstantDate(c.lastRevisedAt)}` : ""}` }),
        progressView({ state: "RECORDED", rate: c.achievementRate }, ""),
      );
    } else {
      parts.push(el("p", { class: "note", testid: "criteria-summary", text: "달성 기준 설정하기: 기준과 배점(합계 100)을 정하고 확정하면 연간 달성률이 표시돼요. 확정 전에는 초안으로 저장할 수 있어요." }));
    }

    if (mode === "edit") parts.push(editor(confirmed));
    else if (mode === "confirm") parts.push(confirmPanel());
    else parts.push(readView());
    ui.criteria.replaceChildren(...parts);
  }

  // 확정 후 읽기 화면: 항목별 달성 토글 (PUT .../achievement)
  function readView() {
    const c = state.criteria;
    const memoInput = el("input", { id: "achieve-memo", type: "text", maxlength: 255, testid: "achieve-memo", placeholder: "예: 첫 프로젝트 배포 완료" });
    const list = el(
      "ul",
      { class: "list-plain", testid: "criteria-list" },
      c.items.map((item, i) => {
        const check = el("input", { type: "checkbox", "aria-label": `${item.text} 달성`, testid: `achieve-${item.id}` });
        check.checked = item.isAchieved;
        check.addEventListener("change", () => toggleAchievement(item, check, memoInput));
        return el(
          "li",
          { class: "action-row", testid: `criterion-${i}` },
          check,
          el(
            "div",
            { class: "action-main" },
            el("div", { class: item.isAchieved ? "action-title done" : "action-title", text: item.text }),
            el("p", { class: "action-meta", text: `배점 ${item.weight}${item.isBaselineAchieved ? " · 확정 때 이미 달성" : ""}${item.achievedAt ? ` · 달성 ${formatInstantDate(item.achievedAt)}` : ""}` }),
          ),
        );
      }),
    );
    return el(
      "div",
      {},
      formField("달성 메모 (선택, 다음 달성 체크에 기록돼요)", memoInput, "memo"),
      list,
      el("div", { class: "btn-row", style: "margin-top:12px" }, el("button", { type: "button", class: "btn btn-outline", text: "기준 개정", testid: "criteria-revise", onclick: () => { mode = "edit"; memo = ""; drawCriteria(); } })),
    );
  }

  async function toggleAchievement(item, check, memoInput) {
    const wanted = check.checked;
    check.disabled = true;
    const body = { achieved: wanted };
    if (memoInput.value.trim()) body.memo = memoInput.value.trim();
    try {
      await api.put(`/api/goals/yearly/${goalId}/criteria/${item.id}/achievement`, { body });
      await reloadCriteriaOnly();
    } catch (error) {
      check.checked = !wanted;
      check.disabled = false;
      handleApiError(error);
    }
  }

  // 편집 화면(초안 또는 개정)
  function editor(confirmed) {
    const form = el("form", { novalidate: true, testid: "criteria-form", "aria-label": "달성 기준 편집" });
    const banner = el("p", { class: "form-error", "data-form-error": true, role: "alert", hidden: true });
    const total = el("p", { class: "note", role: "status", testid: "criteria-total" });
    const rows = el("div");
    const add = el("button", { type: "button", class: "btn btn-secondary btn-small", text: "+ 기준 추가", testid: "criteria-add" });
    const save = el("button", { type: "submit", class: "btn", text: confirmed ? "개정 저장" : "초안 저장", testid: "criteria-save" });

    const refresh = () => {
      const sum = sumOf(draftItems);
      total.textContent = `배점 합계 ${sum} / 100${sum === 100 ? " ✓" : confirmed ? " — 개정하려면 정확히 100이어야 해요" : " — 확정하려면 정확히 100이어야 해요"}`;
      total.style.color = sum === 100 ? "#1a7f4b" : "#8a5a12";
      add.disabled = draftItems.length >= MAX_CRITERIA;
    };

    const drawRows = () => {
      rows.replaceChildren(
        ...draftItems.map((item, i) => {
          const text = el("input", { id: `criterion-text-${i}`, name: `items[${i}].text`, type: "text", maxlength: 255, testid: `criterion-text-${i}`, "aria-label": `기준 ${i + 1} 내용` });
          text.value = item.text;
          text.addEventListener("input", () => (item.text = text.value));
          const weight = el("input", { id: `criterion-weight-${i}`, name: `items[${i}].weight`, type: "number", min: "1", max: "100", step: "1", testid: `criterion-weight-${i}`, "aria-label": `기준 ${i + 1} 배점`, style: "max-width:110px" });
          weight.value = item.weight;
          weight.addEventListener("input", () => {
            item.weight = weight.value;
            refresh(); // 합계를 입력과 동시에 갱신한다(화면 흐름 문서)
          });
          return el(
            "div",
            { class: "onb-row", testid: `criterion-${i}` },
            el("div", { class: "field-row" }, text, weight, el("button", { type: "button", class: "link-button", text: "삭제", "aria-label": `기준 ${i + 1} 삭제`, testid: `criterion-remove-${i}`, onclick: () => { draftItems.splice(i, 1); drawRows(); refresh(); } })),
            el("p", { class: "field-error", "data-error-for": `items[${i}].text` }),
            el("p", { class: "field-error", "data-error-for": `items[${i}].weight` }),
          );
        }),
      );
      refresh();
    };
    add.addEventListener("click", () => {
      draftItems.push({ text: "", weight: "" });
      drawRows();
      rows.querySelector(".onb-row:last-child input")?.focus();
    });

    const memoInput = el("input", { id: "criteria-memo", name: "memo", type: "text", maxlength: 255, testid: "criteria-memo" });
    memoInput.value = memo;
    memoInput.addEventListener("input", () => (memo = memoInput.value));

    form.append(banner, rows, el("div", { class: "btn-row" }, add), total);
    if (confirmed) form.append(formField("개정 메모 (선택)", memoInput, "memo", "진행 기록에 남아요."));
    const buttons = el("div", { class: "btn-row" }, save);
    if (!confirmed) {
      buttons.append(el("button", { type: "button", class: "btn", text: "확정하기", testid: "criteria-confirm", onclick: () => startConfirm(form) }));
    } else {
      buttons.append(el("button", { type: "button", class: "btn btn-outline", text: "취소", testid: "criteria-cancel", onclick: () => { mode = "view"; drawCriteria(); } }));
    }
    form.append(buttons);
    drawRows();

    form.addEventListener("submit", async (event) => {
      event.preventDefault();
      if (!prepare(form, confirmed)) return;
      setBusy(save, true, "저장 중…");
      try {
        await saveDraft(confirmed);
        toast(confirmed ? "기준을 개정했어요." : "초안을 저장했어요.");
        await reload();
      } catch (error) {
        setBusy(save, false);
        handleCriteriaError(error, form);
      }
    });
    return form;
  }

  /** 입력 검증. 빈 행은 버리고(오류 경로의 인덱스가 서버 요청과 일치하도록) 나머지를 확인한다. */
  function prepare(form, needSum) {
    clearFormErrors(form);
    draftItems = draftItems.filter((i) => String(i.text).trim() !== "" || String(i.weight) !== "");
    if (draftItems.length === 0) {
      setFormError(form, "기준을 1개 이상 입력해 주세요.");
      return false;
    }
    let bad = false;
    draftItems.forEach((item, i) => {
      if (!String(item.text).trim()) { setFieldError(form, `items[${i}].text`, "기준 내용을 입력해 주세요."); bad = true; }
      const w = Number(item.weight);
      if (!Number.isInteger(w) || w < 1 || w > 100) { setFieldError(form, `items[${i}].weight`, "배점은 1~100의 정수예요."); bad = true; }
    });
    if (!bad && needSum && sumOf(draftItems) !== 100) {
      setFormError(form, `배점 합계가 ${sumOf(draftItems)}이에요. 정확히 100이어야 해요.`);
      bad = true;
    }
    return !bad;
  }

  /** PUT criteria. 확정 전에는 초안 저장(합계 검사 없음), 확정 후에는 개정(합계 100 필요, memo 기록). */
  async function saveDraft(confirmed) {
    const body = {
      goalVersion: state.criteria.goalVersion,
      items: draftItems.map((i) => (i.id ? { id: i.id, text: i.text.trim(), weight: Number(i.weight) } : { text: i.text.trim(), weight: Number(i.weight) })),
    };
    if (confirmed && memo.trim()) body.memo = memo.trim();
    const saved = await api.put(`/api/goals/yearly/${goalId}/criteria`, { body });
    state.criteria = saved; // 응답이 GET과 같은 형식이라 새 id·goalVersion을 바로 쓸 수 있다
    return saved;
  }

  function handleCriteriaError(error, form) {
    if (error.code === "CRITERIA_WEIGHT_SUM_INVALID") {
      const total = error.details?.totalWeight;
      setFormError(form, `${error.message}${total !== undefined ? ` (현재 합계 ${total})` : ""}`);
      return;
    }
    if (error.code === "CRITERIA_ALREADY_CONFIRMED") {
      toast(error.message, { error: true });
      reload();
      return;
    }
    handleApiError(error, { form, onVersionConflict: reload });
  }

  // 확정: 합계 100을 확인하고 초안을 먼저 저장(서버는 "저장된 초안"을 확정한다)한 뒤, 이미 달성한 기준을 고르게 한다.
  async function startConfirm(form) {
    if (!prepare(form, true)) return;
    const button = form.querySelector('[data-testid="criteria-confirm"]');
    setBusy(button, true, "저장 중…");
    try {
      await saveDraft(false);
      draftItems = state.criteria.items.map((i) => ({ id: i.id, text: i.text, weight: i.weight }));
      baseline = new Set();
      mode = "confirm";
      drawCriteria();
    } catch (error) {
      setBusy(button, false);
      handleCriteriaError(error, form);
    }
  }

  function confirmPanel() {
    const c = state.criteria;
    const rate = el("p", { class: "note", role: "status", testid: "baseline-rate" });
    const refresh = () => {
      const sum = c.items.filter((i) => baseline.has(i.id)).reduce((s, i) => s + i.weight, 0);
      rate.textContent = `최초 기준선 ${sum}% (이미 달성한 기준의 배점 합)`;
    };
    const list = el(
      "ul",
      { class: "list-plain" },
      c.items.map((item) => {
        const check = el("input", { type: "checkbox", id: `baseline-${item.id}`, testid: `baseline-${item.id}` });
        check.checked = baseline.has(item.id);
        check.addEventListener("change", () => {
          if (check.checked) baseline.add(item.id);
          else baseline.delete(item.id);
          refresh();
        });
        return el("li", { class: "action-row" }, check, el("label", { for: `baseline-${item.id}`, class: "action-main", text: `${item.text} (배점 ${item.weight})` }));
      }),
    );
    const banner = el("p", { class: "form-error", "data-form-error": true, role: "alert", hidden: true });
    const panel = el("div", { testid: "confirm-panel" });
    const submit = el("button", { type: "button", class: "btn", text: "확정", testid: "confirm-submit" });
    submit.addEventListener("click", async () => {
      setBusy(submit, true, "확정 중…");
      try {
        await api.post(`/api/goals/yearly/${goalId}/criteria/confirm`, {
          body: { goalVersion: state.criteria.goalVersion, baselineAchievedCriterionIds: [...baseline] },
        });
        toast("달성 기준을 확정했어요.");
        await reload();
      } catch (error) {
        setBusy(submit, false);
        handleCriteriaError(error, panel);
      }
    });
    refresh();
    panel.append(
      el("p", { class: "lead", text: "확정하면 기준과 배점은 개정으로만 바꿀 수 있어요. 이미 달성한 기준이 있다면 골라 주세요. 그 배점이 최초 기준선이 돼요." }),
      list,
      rate,
      banner,
      el("div", { class: "btn-row" }, submit, el("button", { type: "button", class: "btn btn-outline", text: "취소", testid: "confirm-cancel", onclick: () => { mode = "edit"; drawCriteria(); } })),
    );
    return panel;
  }

  // ---------- 하위 목표 트리 ----------
  function drawTree() {
    const months = state.detail.monthlyGoals;
    const parts = [
      el("div", { class: "dash-head" }, el("h2", { id: "tree-heading", style: "margin:0;font-size:17px", text: "하위 목표" }), el("a", { href: "#/plan", class: "btn btn-secondary btn-small", text: "이번 달·주 계획 설정", testid: "plan-link" })),
    ];
    if (months.length === 0) parts.push(el("p", { class: "note", testid: "tree-empty", text: "아직 하위 목표가 없어요. 월간 목표를 만들어 보세요." }));
    for (const m of months) {
      parts.push(
        el(
          "div",
          { class: "plan-card", testid: `tree-monthly-${m.id}` },
          el("div", { class: "plan-top" }, el("span", { text: `${m.month?.replace("-", ".") ?? ""} ${m.title}${suffixOf(m)}` }), el("button", { type: "button", class: "link-button", text: "삭제", "aria-label": `${m.title} 삭제`, testid: `tree-monthly-delete-${m.id}`, onclick: () => removeChild("monthly", m) })),
          progressView(m.progress, `주간 목표 ${m.weeklyGoalCount ?? (m.weeklyGoals ?? []).length}개`),
          ...(m.weeklyGoals ?? []).map((w) =>
            el(
              "div",
              { style: "margin:10px 0 0 16px", testid: `tree-weekly-${w.id}` },
              el("div", { class: "plan-top" }, el("span", { text: `${formatDate(w.startDate)}–${formatDate(w.endDate)} ${w.title}${suffixOf(w)}` }), el("button", { type: "button", class: "link-button", text: "삭제", "aria-label": `${w.title} 삭제`, testid: `tree-weekly-delete-${w.id}`, onclick: () => removeChild("weekly", w) })),
              progressView(w.progress, w.measurementType === "NUMERIC" ? `${w.currentValue} / ${w.targetValue}` : ""),
            ),
          ),
        ),
      );
    }
    ui.tree.replaceChildren(...parts);
  }

  async function removeChild(level, goal) {
    try {
      const done = await deleteGoalFlow(level, goal.id, goal.title, alive);
      if (done && alive()) await reload();
    } catch (error) {
      if (alive()) handleApiError(error, { onVersionConflict: reload });
    }
  }

  // ---------- 진행 기록 ----------
  function drawLogs() {
    const mine = state.logs.filter((l) => l.targetType === "YEARLY_GOAL" && l.targetId === goalId);
    ui.logs.replaceChildren(
      el("h2", { id: "logs-heading", style: "margin:0 0 8px;font-size:17px", text: "이 목표의 진행 기록" }),
      mine.length === 0
        ? el("p", { class: "note", text: "아직 진행 기록이 없어요." })
        : el(
            "ul",
            { class: "list-plain" },
            mine.map((l) =>
              el(
                "li",
                { class: "log-row", testid: `goal-log-${l.id}` },
                el("strong", { text: formatDate(l.logDate) }),
                ` · ${CHANGE_LABEL[l.changeType] ?? TARGET_LABEL[l.targetType] ?? ""} ${l.previousRate ?? "-"}% → ${l.newRate ?? "-"}%`,
                l.memo ? el("div", { class: "action-meta", text: l.memo }) : null,
              ),
            ),
          ),
    );
  }

  loadAll();
}
