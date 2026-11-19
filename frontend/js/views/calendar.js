// 달력 (#/calendar). 월 보기 + 선택한 날의 항목 목록 + 항목 추가·수정·완료·삭제 + 목표 필터.
// 호출 API: GET /api/settings(주 시작 요일·오늘), GET /api/goals/yearly(필터·연결 선택지),
//           GET /api/calendar/items(보이는 칸 범위), POST·PATCH·DELETE /api/calendar/items, PUT .../completion
import { api } from "../api.js";
import { addDays, isValidIsoDate } from "../dateUtil.js";
import {
  clearFormErrors,
  confirmDialog,
  el,
  formatDate,
  formField,
  handleApiError,
  renderLoading,
  setBusy,
  setFieldError,
  toast,
} from "../ui.js";

const WEEKDAY_NAMES = ["일", "월", "화", "수", "목", "금", "토"];
const DAY_INDEX = { SUNDAY: 0, MONDAY: 1, TUESDAY: 2, WEDNESDAY: 3, THURSDAY: 4, FRIDAY: 5, SATURDAY: 6 };
const MAX_CHIPS = 4; // 칸 하나에 칩을 몇 개까지 그릴지. 넘으면 "+N개"로 줄이고 상세는 선택일 목록에서 본다.
const DEFAULT_COLOR = "#3B82F6";

/** 서버가 준 색을 style에 넣기 전에 #RRGGBB 형식만 허용한다(속성 주입 방지). */
const safeColor = (color) => (/^#[0-9a-fA-F]{6}$/.test(color || "") ? color : DEFAULT_COLOR);

// ---------- 날짜 계산 ----------
// 서버가 "오늘"을 계정 시간대로 정하므로 브라우저 시계 대신 settings.today에서 출발하고,
// 달 이동·칸 계산은 UTC 날짜 연산으로 한다(dateUtil.js와 같은 이유: 시간대 때문에 하루가 밀리지 않게).
const monthOf = (iso) => iso.slice(0, 7);
const firstOfMonth = (month) => `${month}-01`;

function shiftMonth(month, delta) {
  const [y, m] = month.split("-").map(Number);
  return new Date(Date.UTC(y, m - 1 + delta, 1)).toISOString().slice(0, 7);
}

function daysInMonth(month) {
  const [y, m] = month.split("-").map(Number);
  return new Date(Date.UTC(y, m, 0)).getUTCDate();
}

/**
 * 월 보기에 그릴 칸의 범위. 첫 주는 주 시작 요일까지 앞쪽 칸을 채우고, 마지막 주도 7칸이 되게 채운다.
 * 한 달은 최대 6주(42일)라서 API의 "최대 100일" 제한 안에 항상 들어오므로 한 번에 조회할 수 있다.
 */
function gridRange(month, weekStartIndex) {
  const [y, m] = month.split("-").map(Number);
  const firstWeekday = new Date(Date.UTC(y, m - 1, 1)).getUTCDay();
  const lead = (firstWeekday - weekStartIndex + 7) % 7;
  const weeks = Math.ceil((lead + daysInMonth(month)) / 7);
  const from = addDays(firstOfMonth(month), -lead);
  return { from, to: addDays(from, weeks * 7 - 1), cellCount: weeks * 7 };
}

function weekdayLabel(iso) {
  const [y, m, d] = iso.split("-").map(Number);
  return WEEKDAY_NAMES[new Date(Date.UTC(y, m - 1, d)).getUTCDay()];
}

const monthTitle = (month) => `${month.slice(0, 4)}년 ${Number(month.slice(5, 7))}월`;

/** 목록에 보여 줄 날짜 설명. 해야 할 일은 마감일, 일정은 하루면 그 날짜, 기간이면 시작–종료. */
function whenText(item) {
  if (item.itemType === "TASK") return `마감 ${formatDate(item.startDate)}`;
  return item.startDate === item.endDate ? formatDate(item.startDate) : `${formatDate(item.startDate)} – ${formatDate(item.endDate)}`;
}

export function render(container, route) {
  renderLoading(container);
  const alive = () => route.isActive();

  // 화면 상태. 서버 값은 모두 여기서만 읽고 바뀔 때마다 draw*()로 다시 그린다.
  const state = {
    settings: null,
    weekStartIndex: 0,
    month: "",
    selectedDay: "",
    goals: [], // 연간 목표 목록(필터·연결 선택지)
    checkedGoalIds: new Set(), // 필터에서 체크된 목표
    includeUnlinked: true,
    items: [], // 보이는 칸 범위의 항목
    range: null,
  };
  // 이전·다음을 연타할 때 늦게 도착한 이전 응답이 최신 화면을 덮어쓰지 않도록 요청마다 번호를 매긴다.
  let loadSeq = 0;
  const ui = {};

  async function init() {
    // 설정과 연간 목표는 서로 독립이라 함께 요청한다.
    const [settings, yearly] = await Promise.all([api.get("/api/settings"), api.get("/api/goals/yearly")]);
    if (!alive()) return;
    state.settings = settings;
    state.weekStartIndex = DAY_INDEX[settings.weekStartDay] ?? 0;
    state.goals = yearly.items ?? [];
    state.checkedGoalIds = new Set(state.goals.map((g) => g.id)); // 처음에는 전체 표시
    state.month = monthOf(settings.today);
    state.selectedDay = settings.today;
    buildLayout();
    await loadItems();
  }

  // ---------- 조회 ----------
  /**
   * 필터 의미(API §4.11): yearlyGoalIds를 생략하면 모든 목표, 값을 주면 그 목표만, 값이 비면(`yearlyGoalIds=`) 목표 연결 항목은 하나도 안 본다.
   * 목표가 전부 체크된 기본 상태는 파라미터를 생략해 "모든 목표"로 보낸다(목록에 없는 다른 연도 목표의 항목도 놓치지 않기 위해).
   * api.js의 query 옵션은 빈 문자열 값을 버리므로 빈 목록(`yearlyGoalIds=`)을 보내려면 주소를 직접 만든다.
   */
  function itemsUrl(range) {
    const parts = [`from=${range.from}`, `to=${range.to}`];
    const allChecked = state.goals.every((g) => state.checkedGoalIds.has(g.id));
    if (!allChecked) parts.push(`yearlyGoalIds=${[...state.checkedGoalIds].join(",")}`);
    parts.push(`includeUnlinked=${state.includeUnlinked}`);
    return `/api/calendar/items?${parts.join("&")}`;
  }

  async function loadItems() {
    const seq = ++loadSeq;
    const range = gridRange(state.month, state.weekStartIndex);
    try {
      const items = await api.get(itemsUrl(range));
      if (!alive() || seq !== loadSeq) return;
      state.items = items;
      state.range = range;
      drawAll();
    } catch (error) {
      if (!alive() || seq !== loadSeq) return;
      ui.gridMessage.textContent = error.message || "달력을 불러오지 못했어요.";
      ui.gridMessage.hidden = false;
      handleApiError(error);
    }
  }

  // ---------- 화면 뼈대(한 번만 만들고 안의 내용만 다시 그린다. 추가 폼에 입력 중인 값이 날아가지 않게) ----------
  function buildLayout() {
    ui.label = el("span", { class: "cal-month-label", "aria-live": "polite", testid: "cal-month-label" });
    ui.gridMessage = el("p", { class: "form-error", role: "alert", hidden: true });
    ui.filters = el("fieldset", { class: "cal-filters" });
    ui.weekdays = el("div", { class: "cal-weekdays", "aria-hidden": "true" });
    ui.grid = el("div", { class: "cal-grid", role: "group", "aria-label": "월간 달력" });
    ui.dayHeading = el("h2", { id: "selected-day-heading", testid: "selected-day" });
    ui.dayList = el("div", { testid: "day-items" });

    const nav = (text, label, testid, onclick) =>
      el("button", { type: "button", class: "btn btn-outline btn-small", text, "aria-label": label, testid, onclick });
    const toolbar = el(
      "div",
      { class: "cal-toolbar" },
      el("h1", { text: "달력" }),
      nav("◀", "이전 달", "cal-prev", () => moveMonth(shiftMonth(state.month, -1))),
      ui.label,
      nav("▶", "다음 달", "cal-next", () => moveMonth(shiftMonth(state.month, 1))),
      nav("오늘", "오늘로 이동", "cal-today", goToday),
    );

    container.replaceChildren(
      el(
        "div",
        { class: "cal-page" },
        el(
          "section",
          { class: "card", "aria-label": "월간 달력" },
          toolbar,
          el("p", { class: "note", text: "해야 할 일은 마감일에, 일정은 해당 날짜에 표시해요. 기간 일정은 시작일부터 종료일까지 보여요." }),
          ui.filters,
          ui.gridMessage,
          ui.weekdays,
          ui.grid,
        ),
        el("section", { class: "card day-panel", "aria-labelledby": "selected-day-heading" }, ui.dayHeading, ui.dayList, buildAddForm()),
      ),
    );
    drawFilters();
  }

  function goToday() {
    state.selectedDay = state.settings.today;
    state.month = monthOf(state.selectedDay);
    syncFormDates();
    loadItems();
  }

  function moveMonth(month) {
    state.month = month;
    // 선택일은 항상 보이는 달 안에 있게 한다: 오늘이 그 달이면 오늘, 아니면 1일.
    state.selectedDay = monthOf(state.settings.today) === month ? state.settings.today : firstOfMonth(month);
    syncFormDates();
    loadItems();
  }

  function selectDay(day) {
    state.selectedDay = day;
    syncFormDates();
    drawGrid();
    drawDay();
  }

  // ---------- 목표 필터 ----------
  function drawFilters() {
    const boxes = state.goals.map((g) => {
      const input = el("input", { type: "checkbox", testid: `filter-goal-${g.id}` });
      input.checked = state.checkedGoalIds.has(g.id);
      input.addEventListener("change", () => {
        if (input.checked) state.checkedGoalIds.add(g.id);
        else state.checkedGoalIds.delete(g.id);
        loadItems();
      });
      return el(
        "label",
        {},
        input,
        el("span", { class: "cal-dot", style: `background:${safeColor(g.displayColor || g.color)}`, "aria-hidden": "true" }),
        g.title,
      );
    });
    const unlinked = el("input", { type: "checkbox", testid: "filter-unlinked" });
    unlinked.checked = state.includeUnlinked;
    unlinked.addEventListener("change", () => {
      state.includeUnlinked = unlinked.checked;
      loadItems();
    });
    ui.filters.replaceChildren(el("legend", { text: "목표 필터" }), ...boxes, el("label", {}, unlinked, "그 밖의 항목(목표 미연결)"));
  }

  // ---------- 월 격자 ----------
  function drawAll() {
    ui.gridMessage.hidden = true;
    drawGrid();
    drawDay();
  }

  /** 각 날짜 칸에 걸치는 항목을 모은다. 기간 일정은 보이는 범위 안의 모든 날에 들어간다. */
  function itemsByDay() {
    const map = new Map();
    for (const item of state.items) {
      const start = item.startDate < state.range.from ? state.range.from : item.startDate;
      const end = item.endDate > state.range.to ? state.range.to : item.endDate;
      for (let day = start; day <= end; day = addDays(day, 1)) {
        if (!map.has(day)) map.set(day, []);
        map.get(day).push(item);
      }
    }
    return map;
  }

  function drawGrid() {
    ui.label.textContent = monthTitle(state.month);
    ui.weekdays.replaceChildren(
      ...Array.from({ length: 7 }, (_, i) => el("div", { text: WEEKDAY_NAMES[(state.weekStartIndex + i) % 7] })),
    );

    const byDay = itemsByDay();
    // cal-item-{id}는 화면에서 유일해야 하므로(기간 일정은 여러 칸에 걸침) 처음 그려지는 칩에만 붙인다.
    const labelled = new Set();
    const cells = [];
    for (let i = 0, day = state.range.from; i < state.range.cellCount; i += 1, day = addDays(day, 1)) {
      const dayItems = byDay.get(day) ?? [];
      const classes = ["cal-cell"];
      if (monthOf(day) !== state.month) classes.push("outside");
      if (day === state.selectedDay) classes.push("selected");
      if (day === state.settings.today) classes.push("today");

      const number = el("button", {
        type: "button",
        class: "cal-daynum",
        text: String(Number(day.slice(8))),
        "aria-label": `${formatDate(day)} (${weekdayLabel(day)}) 항목 ${dayItems.length}개`,
        "aria-pressed": day === state.selectedDay ? "true" : "false",
      });
      const chips = dayItems.slice(0, MAX_CHIPS).map((item) => {
        const first = !labelled.has(item.id);
        labelled.add(item.id);
        return el("span", {
          class: item.isCompleted ? "cal-chip done" : "cal-chip",
          style: `--chip:${safeColor(item.yearlyGoal?.color)}`,
          title: `${item.itemType === "TASK" ? "해야 할 일" : "일정"}: ${item.title}`,
          text: item.title,
          testid: first ? `cal-item-${item.id}` : false,
          "data-item-id": String(item.id),
        });
      });
      const more = dayItems.length - MAX_CHIPS;
      const cell = el(
        "div",
        { class: classes.join(" "), testid: `cal-day-${day}`, "data-date": day },
        number,
        el("div", { class: "cal-chips" }, chips, more > 0 ? el("span", { class: "cal-more", text: `+${more}개` }) : null),
      );
      // 칸의 빈 곳이나 칩을 눌러도 그 날짜가 선택되게 한다(화면 흐름 문서). 키보드는 날짜 숫자 버튼이 담당한다.
      cell.addEventListener("click", () => selectDay(day));
      cells.push(cell);
    }
    ui.grid.replaceChildren(...cells);
  }

  // ---------- 선택한 날의 항목 목록 ----------
  function drawDay() {
    const day = state.selectedDay;
    ui.dayHeading.textContent = `선택 날짜: ${formatDate(day)} (${weekdayLabel(day)})`;
    const list = state.items.filter((item) => item.startDate <= day && item.endDate >= day);
    ui.dayList.replaceChildren(
      list.length === 0
        ? el("p", { class: "note", testid: "day-items-empty", text: "이 날짜에 항목이 없어요. 아래에서 추가해 보세요." })
        : el("ul", { class: "list-plain" }, list.map(dayRow)),
    );
  }

  function dayRow(item) {
    const check = el("input", { type: "checkbox", "aria-label": `${item.title} 완료`, testid: `item-check-${item.id}` });
    check.checked = Boolean(item.isCompleted);
    check.addEventListener("change", () => toggleComplete(check, item));

    const goal = item.yearlyGoal;
    return el(
      "li",
      { class: item.isCompleted ? "day-item done" : "day-item", testid: `day-item-${item.id}` },
      check,
      el(
        "div",
        { class: "action-main" },
        el(
          "div",
          { class: "action-title" },
          goal ? el("span", { class: "cal-dot", style: `background:${safeColor(goal.color)}`, "aria-hidden": "true" }) : null,
          item.title,
        ),
        el("p", { class: "action-meta", text: [item.itemType === "TASK" ? "해야 할 일" : "일정", whenText(item), goal ? goal.title : "목표 미연결"].join(" · ") }),
        item.description ? el("p", { class: "action-meta", text: item.description }) : null,
      ),
      el(
        "div",
        { class: "action-buttons" },
        el("button", { type: "button", class: "link-button", text: "수정", "aria-label": `${item.title} 수정`, testid: `item-edit-${item.id}`, onclick: () => openEditDialog(item) }),
        el("button", { type: "button", class: "link-button", text: "삭제", "aria-label": `${item.title} 삭제`, testid: `item-delete-${item.id}`, onclick: () => deleteItem(item) }),
      ),
    );
  }

  async function toggleComplete(check, item) {
    check.disabled = true;
    try {
      // 완료는 "상태 지정" 요청이라 version이 필요 없고(API §2.4) 같은 요청을 반복해도 결과가 같다.
      await api.put(`/api/calendar/items/${item.id}/completion`, { body: { completed: check.checked } });
      await loadItems();
    } catch (error) {
      check.checked = !check.checked; // 실패하면 체크 상태를 되돌린다
      check.disabled = false;
      handleApiError(error);
    }
  }

  async function deleteItem(item) {
    const ok = await confirmDialog({
      title: `"${item.title}"을(를) 삭제할까요?`,
      message: "달력 항목은 휴지통 없이 바로 영구 삭제돼요. 삭제하면 되돌릴 수 없어요.",
      confirmText: "삭제",
    });
    if (!ok || !alive()) return; // 확인 중 화면을 떠났다면 삭제하지 않는다(사용자가 다른 화면에서 한 결정이 아니다)
    try {
      await api.delete(`/api/calendar/items/${item.id}`);
      toast("항목을 삭제했어요.");
      await loadItems();
    } catch (error) {
      handleApiError(error);
      if (error.status === 404) loadItems(); // 이미 지워진 항목이면 목록만 새로 맞춘다
    }
  }

  // ---------- 공통 입력칸 ----------
  const textArea = (attrs) => el("textarea", { class: "text-area", rows: "2", maxlength: 2000, ...attrs });

  function goalSelect(id, testid, selectedId, extraGoal) {
    const select = el("select", { id, name: "yearlyGoalId", testid }, el("option", { value: "", text: "연결 안 함" }));
    const goals = [...state.goals];
    // 연결된 목표가 현재 목록(올해 활성 목표)에 없을 때 선택이 풀려 연결이 의도치 않게 바뀌지 않도록 임시 선택지를 둔다.
    if (extraGoal && !goals.some((g) => g.id === extraGoal.id)) goals.unshift({ id: extraGoal.id, title: `${extraGoal.title} (현재 연결)` });
    for (const g of goals) select.append(el("option", { value: String(g.id), text: g.title }));
    select.value = selectedId === null || selectedId === undefined ? "" : String(selectedId);
    return select;
  }

  // ---------- 항목 추가 폼 ----------
  function buildAddForm() {
    const radio = (value, text, testid, checked) => {
      const input = el("input", { type: "radio", name: "itemType", value, testid });
      input.checked = checked;
      return el("label", {}, input, text);
    };
    const title = el("input", { id: "item-title", name: "title", type: "text", maxlength: 255, testid: "item-title", autocomplete: "off" });
    const description = textArea({ id: "item-description", name: "description", testid: "item-description" });
    const start = el("input", { id: "item-start", name: "startDate", type: "date", testid: "item-start" });
    const end = el("input", { id: "item-end", name: "endDate", type: "date", testid: "item-end" });
    const goal = goalSelect("item-goal", "item-goal", null);
    const submit = el("button", { type: "submit", class: "btn", text: "추가", testid: "item-submit" });
    const startField = formField("마감일", start, "startDate");
    const endField = formField("종료일", end, "endDate", "비워 두면 하루 일정이에요.");
    endField.hidden = true;

    const form = el(
      "form",
      { class: "item-form", novalidate: true, testid: "item-form", "aria-label": "달력 항목 추가" },
      el("h3", { text: "항목 추가" }),
      el("p", { class: "form-error", "data-form-error": true, role: "alert", hidden: true }),
      el(
        "fieldset",
        { class: "radio-inline" },
        el("legend", { class: "sr-only", text: "항목 종류" }),
        radio("TASK", "해야 할 일", "item-type-task", true),
        radio("EVENT", "일정", "item-type-event", false),
      ),
      formField("제목", title, "title"),
      formField("설명 (선택)", description, "description"),
      el("div", { class: "item-form-dates" }, startField, endField),
      formField("연결할 연간 목표 (선택)", goal, "yearlyGoalId"),
      el("div", {}, submit),
    );

    const currentType = () => form.querySelector('input[name="itemType"]:checked').value;
    // 종류에 따라 입력칸이 달라진다: 해야 할 일은 마감일 하나, 일정은 시작일·종료일.
    form.addEventListener("change", (event) => {
      if (event.target.name !== "itemType") return;
      const isEvent = currentType() === "EVENT";
      endField.hidden = !isEvent;
      startField.querySelector("label").textContent = isEvent ? "시작일" : "마감일";
    });

    ui.addForm = { start, end };
    start.value = state.selectedDay;

    form.addEventListener("submit", async (event) => {
      event.preventDefault();
      clearFormErrors(form);
      const type = currentType();
      const text = title.value.trim();
      if (!text) return setFieldError(form, "title", "제목을 입력해 주세요.");
      if (!isValidIsoDate(start.value)) return setFieldError(form, "startDate", "올바른 날짜를 입력해 주세요.");
      if (type === "EVENT" && end.value) {
        if (!isValidIsoDate(end.value)) return setFieldError(form, "endDate", "올바른 날짜를 입력해 주세요.");
        if (end.value < start.value) return setFieldError(form, "endDate", "종료일은 시작일과 같거나 이후여야 해요.");
      }
      // 명세: TASK는 endDate를 보내지 않고(마감일 = startDate), EVENT는 생략하면 하루 일정이다.
      const body = {
        itemType: type,
        title: text,
        description: description.value.trim() || null,
        startDate: start.value,
        yearlyGoalId: goal.value === "" ? null : Number(goal.value),
      };
      if (type === "EVENT" && end.value) body.endDate = end.value;

      setBusy(submit, true, "추가 중…");
      try {
        const created = await api.post("/api/calendar/items", { body });
        setBusy(submit, false);
        title.value = "";
        description.value = "";
        end.value = "";
        toast("항목을 추가했어요.");
        // 만든 항목이 보이는 달 밖이면 그 달로 옮겨 바로 확인할 수 있게 한다.
        const day = created?.startDate ?? body.startDate;
        if (monthOf(day) !== state.month) {
          state.month = monthOf(day);
          state.selectedDay = day;
        }
        await loadItems();
      } catch (error) {
        setBusy(submit, false);
        handleApiError(error, { form });
      }
    });
    return form;
  }

  /** 선택한 날짜가 바뀌면 추가 폼의 시작일을 맞춘다(종료일은 비운다). */
  function syncFormDates() {
    if (!ui.addForm) return;
    ui.addForm.start.value = state.selectedDay;
    ui.addForm.end.value = "";
  }

  // ---------- 항목 수정 ----------
  function openEditDialog(item) {
    const dialog = el("dialog", { class: "modal wide", "aria-labelledby": "item-edit-heading", testid: "item-edit-dialog" });
    const isEvent = item.itemType === "EVENT";
    const form = el("form", { novalidate: true, method: "dialog" });
    const banner = el("p", { class: "form-error", "data-form-error": true, role: "alert", hidden: true });
    const title = el("input", { id: "item-edit-title", name: "title", type: "text", maxlength: 255, testid: "item-edit-title" });
    title.value = item.title;
    const description = textArea({ id: "item-edit-description", name: "description", testid: "item-edit-description" });
    description.value = item.description ?? "";
    const start = el("input", { id: "item-edit-start", name: "startDate", type: "date", testid: "item-edit-start" });
    start.value = item.startDate;
    const end = el("input", { id: "item-edit-end", name: "endDate", type: "date", testid: "item-edit-end" });
    end.value = isEvent && item.endDate !== item.startDate ? item.endDate : "";
    const goal = goalSelect("item-edit-goal", "item-edit-goal", item.yearlyGoal?.id ?? null, item.yearlyGoal);

    const save = el("button", { type: "submit", class: "btn", text: "저장", testid: "item-edit-save" });
    const cancel = el("button", { type: "button", class: "btn btn-outline", text: "취소", testid: "item-edit-cancel", onclick: () => dialog.close() });
    form.append(
      banner,
      // 종류는 바꿀 수 없다(PATCH 불변, 422 CALENDAR_ITEM_TYPE_IMMUTABLE). 입력으로 두지 않고 안내만 한다.
      el("p", { class: "note", testid: "item-edit-type", text: `종류: ${isEvent ? "일정" : "해야 할 일"} (종류는 바꿀 수 없어요. 다른 종류가 필요하면 삭제하고 새로 만들어 주세요.)` }),
      formField("제목", title, "title"),
      formField("설명 (선택)", description, "description"),
      formField(isEvent ? "시작일" : "마감일", start, "startDate"),
      isEvent ? formField("종료일", end, "endDate", "비워 두면 하루 일정이에요.") : null,
      formField("연결할 연간 목표", goal, "yearlyGoalId"),
      el("div", { class: "modal-actions" }, cancel, save),
    );
    dialog.append(el("h2", { id: "item-edit-heading", text: isEvent ? "일정 수정" : "해야 할 일 수정" }), form);
    dialog.addEventListener("close", () => dialog.remove());

    form.addEventListener("submit", async (event) => {
      event.preventDefault();
      clearFormErrors(form);
      const newTitle = title.value.trim();
      if (!newTitle) return setFieldError(form, "title", "제목을 입력해 주세요.");
      if (!isValidIsoDate(start.value)) return setFieldError(form, "startDate", "올바른 날짜를 입력해 주세요.");
      if (isEvent && end.value && !isValidIsoDate(end.value)) return setFieldError(form, "endDate", "올바른 날짜를 입력해 주세요.");
      if (isEvent && end.value && end.value < start.value) return setFieldError(form, "endDate", "종료일은 시작일과 같거나 이후여야 해요.");

      // 바뀐 필드만 보낸다. 서버가 안 바뀐 값까지 다시 검사하지 않게 하려는 것이다.
      const body = { version: item.version };
      if (newTitle !== item.title) body.title = newTitle;
      const newDescription = description.value.trim();
      if (newDescription !== (item.description ?? "")) body.description = newDescription === "" ? null : newDescription;
      // 날짜: EVENT는 시작일만 종료일 뒤로 옮기면 400이 나므로(§4.11) 둘 중 하나라도 바뀌면 두 값을 함께 보낸다.
      // 종료일을 비우면 endDate:null(하루 일정). TASK는 종료일 = 시작일이라 startDate만 보낸다.
      const effectiveEnd = isEvent ? end.value || start.value : start.value;
      if (start.value !== item.startDate || effectiveEnd !== item.endDate) {
        body.startDate = start.value;
        if (isEvent) body.endDate = end.value === "" ? null : end.value;
      }
      const newGoal = goal.value === "" ? null : Number(goal.value);
      if (newGoal !== (item.yearlyGoal?.id ?? null)) body.yearlyGoalId = newGoal; // null은 연결 해제
      if (Object.keys(body).length === 1) {
        dialog.close();
        return;
      }

      setBusy(save, true, "저장 중…");
      try {
        await api.patch(`/api/calendar/items/${item.id}`, { body });
        dialog.close();
        toast("항목을 수정했어요.");
        await loadItems();
      } catch (error) {
        setBusy(save, false);
        handleApiError(error, {
          form,
          // 다른 곳에서 먼저 수정된 경우: 창을 닫고 최신 목록을 다시 불러온다(API §2.4).
          onVersionConflict: () => {
            dialog.close();
            loadItems();
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
    container.replaceChildren(
      el("h1", { class: "page-title", text: "달력" }),
      el("p", { class: "form-error", role: "alert", text: error.message || "달력을 불러오지 못했어요." }),
    );
    handleApiError(error);
  });
}
