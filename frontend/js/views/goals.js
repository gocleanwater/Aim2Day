// 연간 목표 목록 (#/goals). GET /api/goals/yearly → {year, activeCount, maxCount, items}
// 새 목표(POST /api/goals/yearly), 순서 저장(PUT /api/goals/yearly/order), 카드에서 수정(PATCH)·삭제(DELETE).
import { api } from "../api.js";
import {
  clearFormErrors,
  el,
  formField,
  handleApiError,
  renderLoading,
  setBusy,
  setFieldError,
  toast,
} from "../ui.js";
import { DEFAULT_COLOR, STATUS_LABELS, deleteGoalFlow, normalizeColor } from "./goalCommon.js";
import { progressView } from "./dashboardSections.js";

export function render(container, route) {
  renderLoading(container);
  const alive = () => route.isActive();
  let data = null; // 마지막으로 받은 목록
  let reorder = null; // 순서 편집 중이면 {ids:[...]} (저장 전까지 화면에서만 바뀐다)

  async function load() {
    try {
      data = await api.get("/api/goals/yearly");
    } catch (error) {
      if (!alive()) return;
      container.replaceChildren(
        el("h1", { class: "page-title", text: "연간 목표" }),
        el("p", { class: "form-error", role: "alert", text: error.message }),
      );
      handleApiError(error);
      return;
    }
    if (!alive()) return;
    reorder = null;
    draw();
  }

  const atLimit = () => data.activeCount >= data.maxCount;

  function draw() {
    const header = el(
      "div",
      { class: "dash-head" },
      el("h1", { class: "page-title", style: "margin:0", text: `연간 목표 ${data.year}년` }),
      el(
        "div",
        { class: "btn-row" },
        el("span", { class: "note", testid: "goal-count", text: `${data.activeCount} / ${data.maxCount}개` }),
        el("button", {
          type: "button",
          class: "btn btn-outline btn-small",
          text: reorder ? "순서 편집 중" : "순서 편집",
          disabled: Boolean(reorder) || data.items.length < 2,
          testid: "reorder-toggle",
          onclick: () => {
            reorder = { ids: data.items.map((g) => g.id) };
            draw();
          },
        }),
        el("a", { href: "#/goals/trash", class: "btn btn-secondary btn-small", text: "삭제된 목표 보기", testid: "trash-link" }),
      ),
    );

    container.replaceChildren(header, newGoalCard(), reorder ? reorderCard() : cards());
  }

  // ---------- 새 목표 ----------
  function newGoalCard() {
    const title = el("input", { id: "new-goal-title", name: "title", type: "text", maxlength: 100, testid: "new-goal-title", placeholder: "예: 백엔드 개발자 취업 준비" });
    const description = el("input", { id: "new-goal-description", name: "description", type: "text", maxlength: 500, testid: "new-goal-description" });
    const color = el("input", { id: "new-goal-color", name: "color", type: "color", value: DEFAULT_COLOR.toLowerCase(), testid: "new-goal-color", style: "min-height:46px;padding:4px" });
    const submit = el("button", { type: "submit", class: "btn", text: "목표 추가", disabled: atLimit(), testid: "new-goal-submit" });
    const form = el(
      "form",
      { novalidate: true, testid: "new-goal-form", "aria-label": "새 연간 목표" },
      el("p", { class: "form-error", "data-form-error": true, role: "alert", hidden: true }),
      formField("연간 목표 제목", title, "title"),
      formField("설명 (선택)", description, "description"),
      formField("표시 색상", color, "color"),
      submit,
    );
    if (atLimit()) {
      // 한도에 도달하면 추가를 막고 현재·최대 개수와 가능한 조치를 안내한다(화면 흐름 문서).
      title.disabled = description.disabled = color.disabled = true;
    }
    form.addEventListener("submit", async (event) => {
      event.preventDefault();
      clearFormErrors(form);
      const text = title.value.trim();
      if (!text) return setFieldError(form, "title", "목표 제목을 입력해 주세요.");
      setBusy(submit, true, "추가 중…");
      try {
        await api.post("/api/goals/yearly", {
          body: { title: text, description: description.value.trim() || null, color: normalizeColor(color.value) },
        });
        toast("연간 목표를 추가했어요.");
        await load();
      } catch (error) {
        setBusy(submit, false);
        handleApiError(error, { form }); // 409 GOAL_LIMIT_EXCEEDED는 서버 message를 배너로 보여 준다
        if (error.code === "GOAL_LIMIT_EXCEEDED") load();
      }
    });
    return el(
      "section",
      { class: "card", "aria-labelledby": "new-goal-heading" },
      el("h2", { id: "new-goal-heading", style: "margin:0 0 12px;font-size:17px", text: "새 연간 목표" }),
      atLimit()
        ? el("p", {
            class: "form-error",
            role: "status",
            testid: "goal-limit",
            text: `${data.year}년 목표는 최대 ${data.maxCount}개까지 만들 수 있어요. 현재 ${data.activeCount}개예요. 기존 목표를 삭제한 뒤 다시 시도해 주세요.`,
          })
        : el("p", { class: "note", text: `${data.year}년 목표는 최대 ${data.maxCount}개까지 만들 수 있어요(진행·완료·보류·중단 모두 포함).` }),
      form,
    );
  }

  // ---------- 카드 ----------
  function cards() {
    if (data.items.length === 0) {
      return el("p", { class: "note", testid: "goals-empty", text: "아직 연간 목표가 없어요. 위에서 새 목표를 추가해 보세요." });
    }
    return el(
      "div",
      { class: "goal-grid", testid: "goal-cards" },
      data.items.map((g) => goalCard(g)),
    );
  }

  function goalCard(g) {
    const configured = g.criteria?.state === "CONFIRMED" && g.criteria.achievementRate !== null && g.criteria.achievementRate !== undefined;
    const suffix = g.pathStatus === "ON_HOLD" ? " (보류)" : g.pathStatus === "DROPPED" ? " (중단)" : "";
    return el(
      "article",
      { class: "card goal-card", testid: `goal-card-${g.id}`, style: `border-top:6px solid ${g.displayColor || g.color || DEFAULT_COLOR}` },
      el("h2", { style: "margin:0;font-size:17px", text: g.title + suffix }),
      el(
        "p",
        { class: "action-meta" },
        el("span", { class: "badge", text: STATUS_LABELS[g.status] ?? g.status }),
        g.isDelayed ? el("span", { class: "badge warn", text: "기한 지남" }) : null,
      ),
      g.description ? el("p", { class: "action-meta", text: g.description }) : null,
      configured
        ? progressView({ state: "RECORDED", rate: g.criteria.achievementRate }, "달성 기준 배점 합")
        : el("p", {}, el("a", { href: `#/goals/${g.id}`, text: "달성 기준 설정하기", testid: `setup-criteria-${g.id}` })),
      el(
        "div",
        { class: "btn-row", style: "margin-top:12px" },
        el("a", { href: `#/goals/${g.id}`, class: "btn btn-secondary btn-small", text: "상세", testid: `goal-detail-${g.id}` }),
        el("button", { type: "button", class: "btn btn-outline btn-small", text: "수정", "aria-label": `${g.title} 수정`, testid: `goal-edit-${g.id}`, onclick: () => openEdit(g) }),
        el("button", { type: "button", class: "btn btn-outline btn-small", text: "삭제", "aria-label": `${g.title} 삭제`, testid: `goal-delete-${g.id}`, onclick: () => remove(g) }),
      ),
    );
  }

  async function remove(g) {
    try {
      const done = await deleteGoalFlow("yearly", g.id, g.title, alive);
      if (done && alive()) await load();
    } catch (error) {
      if (alive()) handleApiError(error, { onVersionConflict: load });
    }
  }

  function openEdit(g) {
    const dialog = el("dialog", { class: "modal", "aria-labelledby": "goal-edit-title", testid: "goal-edit-dialog" });
    const form = el("form", { novalidate: true });
    const title = el("input", { id: "goal-edit-title-input", name: "title", type: "text", maxlength: 100, testid: "goal-edit-title" });
    title.value = g.title;
    const description = el("input", { id: "goal-edit-description", name: "description", type: "text", maxlength: 500, testid: "goal-edit-description" });
    description.value = g.description ?? "";
    const color = el("input", { id: "goal-edit-color", name: "color", type: "color", testid: "goal-edit-color", style: "min-height:46px;padding:4px" });
    color.value = (g.color || DEFAULT_COLOR).toLowerCase();
    const save = el("button", { type: "submit", class: "btn", text: "저장", testid: "goal-edit-save" });
    const cancel = el("button", { type: "button", class: "btn btn-outline", text: "취소", onclick: () => dialog.close() });
    form.append(
      el("p", { class: "form-error", "data-form-error": true, role: "alert", hidden: true }),
      formField("제목", title, "title"),
      formField("설명", description, "description"),
      formField("표시 색상", color, "color"),
      el("div", { class: "modal-actions" }, cancel, save),
    );
    dialog.append(el("h2", { id: "goal-edit-title", text: "연간 목표 수정" }), form);
    dialog.addEventListener("close", () => dialog.remove());
    form.addEventListener("submit", async (event) => {
      event.preventDefault();
      clearFormErrors(form);
      const text = title.value.trim();
      if (!text) return setFieldError(form, "title", "목표 제목을 입력해 주세요.");
      const body = { version: g.version };
      if (text !== g.title) body.title = text;
      if ((description.value.trim() || null) !== (g.description ?? null)) body.description = description.value.trim() || null;
      const newColor = normalizeColor(color.value);
      if (newColor !== (g.color || "").toUpperCase()) body.color = newColor;
      if (Object.keys(body).length === 1) return dialog.close();
      setBusy(save, true, "저장 중…");
      try {
        await api.patch(`/api/goals/yearly/${g.id}`, { body });
        dialog.close();
        toast("수정했어요.");
        await load();
      } catch (error) {
        setBusy(save, false);
        // 409: 다른 곳에서 먼저 고쳐졌으니 창을 닫고 최신 목록을 다시 불러온다.
        handleApiError(error, {
          form,
          onVersionConflict: () => {
            dialog.close();
            load();
          },
        });
      }
    });
    document.body.append(dialog);
    dialog.showModal();
    title.focus();
  }

  // ---------- 순서 편집 ----------
  // 드래그 대신 위/아래 버튼으로 옮긴다(키보드·보조 기술 사용자도 쓸 수 있다). 저장해야 서버에 반영된다.
  function reorderCard() {
    const byId = new Map(data.items.map((g) => [g.id, g]));
    const list = el("ol", { class: "list-plain", testid: "reorder-list" });
    reorder.ids.forEach((id, index) => {
      const g = byId.get(id);
      const move = (delta) => {
        const j = index + delta;
        if (j < 0 || j >= reorder.ids.length) return;
        [reorder.ids[index], reorder.ids[j]] = [reorder.ids[j], reorder.ids[index]];
        draw();
        // 다시 그린 뒤에도 같은 버튼에 포커스를 두어 연속으로 옮길 수 있게 한다.
        container.querySelector(`[data-testid="reorder-${delta < 0 ? "up" : "down"}-${id}"]`)?.focus();
      };
      list.append(
        el(
          "li",
          { class: "action-row", testid: `reorder-item-${id}` },
          el("span", { class: "action-main", text: `${index + 1}. ${g.title}` }),
          el("div", { class: "action-buttons" },
            el("button", { type: "button", class: "btn btn-outline btn-small", text: "▲", "aria-label": `${g.title} 위로`, disabled: index === 0, testid: `reorder-up-${id}`, onclick: () => move(-1) }),
            el("button", { type: "button", class: "btn btn-outline btn-small", text: "▼", "aria-label": `${g.title} 아래로`, disabled: index === reorder.ids.length - 1, testid: `reorder-down-${id}`, onclick: () => move(1) }),
          ),
        ),
      );
    });
    const save = el("button", { type: "button", class: "btn", text: "저장", testid: "reorder-save" });
    save.addEventListener("click", async () => {
      setBusy(save, true, "저장 중…");
      try {
        await api.put("/api/goals/yearly/order", { body: { year: data.year, goalIds: reorder.ids } });
        toast("순서를 저장했어요.");
        await load();
      } catch (error) {
        setBusy(save, false);
        // 422 GOAL_ORDER_MISMATCH: 다른 곳에서 목표가 늘거나 줄었다. 서버 message를 보이고 최신 목록으로 되돌린다.
        handleApiError(error);
        if (error.code === "GOAL_ORDER_MISMATCH") load();
      }
    });
    return el(
      "section",
      { class: "card", "aria-labelledby": "reorder-heading" },
      el("h2", { id: "reorder-heading", style: "margin:0 0 12px;font-size:17px", text: "순서 편집" }),
      list,
      el(
        "div",
        { class: "btn-row", style: "margin-top:12px" },
        save,
        el("button", { type: "button", class: "btn btn-outline", text: "취소", testid: "reorder-cancel", onclick: () => { reorder = null; draw(); } }),
      ),
    );
  }

  load();
}
