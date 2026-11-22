// 대시보드의 "오늘의 중요도·긴급도" 사분면. 중요도 x(오른쪽이 중요), 긴급도 y(위쪽이 급함), 각각 0~100 정수(API §4.10).
//
// 서버 상태 5가지(placement.state)와 화면 표시:
//   UNCLASSIFIED        트레이
//   PENDING             사분면(미확정 점 스타일)
//   CONFIRMED           사분면
//   RECLASSIFYING       사분면의 새 위치 + 확정 위치의 흐린 표시(ghost)
//   PENDING_UNCLASSIFY  트레이(미확정 점 스타일) + 확정 위치의 흐린 표시
// 서버는 점을 옮길 때마다 "미확정" 위치만 저장하고, [오늘 분류 확정]을 눌러야 확정 좌표가 바뀐다(결정 9).
//
// 조작: 드래그(포인터), 점 선택 후 칸 클릭, 방향키(Shift는 10칸), Enter(트레이의 점을 배치), Delete(트레이로).
// 모든 조작은 PUT .../placement 하나로 서버에 저장한다(결정 19). 방향키처럼 연속 입력은 마지막 위치만 보내도록 잠깐 모았다가(디바운스) 보낸다.
import { api } from "../api.js";
import { el, handleApiError, toast } from "../ui.js";

const CELLS = {
  Q1: { label: "중요 · 급함", center: [75, 75] },
  Q2: { label: "덜 중요 · 급함", center: [25, 75] },
  Q3: { label: "덜 중요 · 여유", center: [25, 25] },
  Q4: { label: "중요 · 여유", center: [75, 25] },
};
const UNCONFIRMED = new Set(["PENDING", "RECLASSIFYING", "PENDING_UNCLASSIFY"]);
const SAVE_DELAY_MS = 350;
const GOAL_LEFT_MESSAGE = "목표에 연결된 일은 중요 영역(오른쪽)에만 놓을 수 있어요.";

// 좌표(0~100)가 속한 칸. 중요도 x>=50 이면 오른쪽, 긴급도 y>=50 이면 위쪽. Q1 중요·급함, Q2 덜 중요·급함, Q3 덜 중요·여유, Q4 중요·여유.
export function cellOf(x, y) {
  return y >= 50 ? (x >= 50 ? "Q1" : "Q2") : x >= 50 ? "Q4" : "Q3";
}

/** 목록 행에 보여 줄 점 상태 문구(화면 흐름 문서 "오늘 행동 영역"). */
export function placementText(action) {
  const p = action.placement;
  if (!p) return "";
  const c = p.confirmed;
  const d = p.pending;
  const at = (pos) => `중요도 ${pos.importance} · 긴급도 ${pos.urgency}`;
  switch (p.state) {
    case "CONFIRMED":
      return `확정 · ${at(c)}`;
    case "PENDING":
      return `미확정 · ${at(d)}`;
    case "RECLASSIFYING":
      return `재분류 중 · ${at(d)} (확정 위치 ${at(c)})`;
    case "PENDING_UNCLASSIFY":
      return `되돌리기 대기 · 확정 위치 ${at(c)}`;
    default:
      return "미분류";
  }
}

// 목표 연결 행동의 소속 경로("연간 › 월간 › 주간")와 보류·중단 꼬리표. 그 밖의 할 일은 빈 문자열이다.
export function pathText(action) {
  const g = action.goalPath;
  if (!g) return "";
  const text = [g.yearlyGoal?.title, g.monthlyGoal?.title, g.weeklyGoal?.title].filter(Boolean).join(" › ");
  const suffix = action.pathStatus === "ON_HOLD" ? " (보류)" : action.pathStatus === "DROPPED" ? " (중단)" : "";
  return text + suffix;
}

/**
 * @param {object} deps
 * @param {HTMLElement} deps.host 사분면을 그릴 자리
 * @param {{data: object}} deps.state 대시보드 상태(state.data.items가 오늘 행동 목록)
 * @param {() => Promise<void>} deps.reload 서버 상태로 행동 목록을 다시 읽는다(확정·취소·오류 뒤)
 * @param {() => void} deps.refreshList 점 상태 문구가 바뀌었을 때 목록 행을 다시 그린다
 */
export function createQuadrant({ host, state, reload, refreshList }) {
  let selectedId = null;
  let dragged = false; // 드래그 직후 발생하는 click이 "선택"으로 처리되지 않게 한다
  const timers = new Map(); // 행동 id → 디바운스 타이머
  const saving = new Map(); // 행동 id → 진행 중인 저장(같은 행동의 요청을 순서대로 보내기 위함)

  // ---------- 골격 ----------
  const prompt = el("p", { class: "lead", testid: "quad-prompt" });
  const counts = el("p", { class: "note", role: "status", testid: "quad-counts" });
  const tray = el("div", { class: "unclassified-tray", "aria-label": "미분류 할 일", testid: "quad-tray" });
  const quadrants = el("div", { class: "quadrants", testid: "quad-plane" });
  const detail = el("div", { class: "point-detail", "aria-live": "polite", testid: "quad-detail" });
  const status = el("span", { class: "note", role: "status", testid: "quad-status" });
  const confirmButton = el("button", { type: "button", class: "btn", text: "오늘 분류 확정", testid: "quad-confirm" });
  const cancelButton = el("button", { type: "button", class: "btn btn-outline", text: "변경 취소", testid: "quad-cancel" });
  const trayButton = el("button", { type: "button", class: "btn btn-outline", text: "선택한 점 트레이로 보내기", testid: "quad-to-tray" });
  const aiButton = el("button", { type: "button", class: "link-button", text: "AI 추천 · 준비 중" });

  // DOM 순서가 격자 순서(왼쪽 위부터)이므로 Q2, Q1, Q3, Q4 순으로 둔다.
  for (const key of ["Q2", "Q1", "Q3", "Q4"]) {
    quadrants.append(
      el("button", {
        type: "button",
        class: "quad-cell",
        "data-cell": key,
        testid: `quadrant-${key}`,
        "aria-label": `${CELLS[key].label} 칸에 선택한 점 놓기`,
        text: CELLS[key].label,
        onclick: () => placeSelectedInCell(key),
      }),
    );
  }

  host.replaceChildren(
    prompt,
    counts,
    el("p", {
      class: "note",
      text: "미분류 대기 트레이 · 점을 아래 좌표 평면으로 끌어 놓고, 되돌릴 점은 이 트레이로 끌어 놓으세요. 점을 선택한 뒤 칸을 눌러도 놓을 수 있어요.",
    }),
    tray,
    el(
      "figure",
      { class: "priority-plot", "aria-label": "오늘의 중요도·긴급도 좌표판", style: "margin-left:0;margin-right:0" },
      el("span", { class: "axis axis-top", text: "급함 ↑" }),
      el("span", { class: "axis axis-left", text: "중요도 낮음" }),
      el("span", { class: "axis axis-right", text: "중요함 →" }),
      el("span", { class: "axis axis-bottom", text: "여유 있음" }),
      quadrants,
    ),
    detail,
    el("div", { class: "btn-row" }, confirmButton, cancelButton, trayButton, aiButton, status),
  );
  detail.textContent = "점에 포인터를 올리거나 선택해 행동 상세를 확인하세요. 방향키로 위치를 조정할 수 있어요.";

  aiButton.addEventListener("click", () => toast("해당 기능은 현재 개발 중입니다."));
  trayButton.addEventListener("click", () => {
    const action = selected();
    if (!action) return say("먼저 점을 선택해 주세요.");
    sendToTray(action);
  });
  confirmButton.addEventListener("click", confirmAll);
  cancelButton.addEventListener("click", cancelAll);

  // ---------- 상태 도우미 ----------
  const items = () => state.data?.items ?? [];
  const selected = () => items().find((a) => a.id === selectedId) ?? null;
  const isGoal = (a) => a.type === "GOAL";
  const say = (text) => (status.textContent = text);
  const stateOf = (a) => a.placement?.state ?? "UNCLASSIFIED";

  // 서버가 정한 "지금 화면에 표시할 위치"({x,y}). 트레이에 있으면 null. 서버는 importance/urgency로 주고 화면은 x/y로 다룬다.
  function displayed(a) {
    const d = a.placement?.displayed;
    return d ? { x: d.importance, y: d.urgency } : null;
  }

  // 화면용 표시 위치를 바꾼다(서버 저장 전 즉시 반응용). 서버 응답이 오면 placement 전체로 덮어쓴다.
  function setDisplayed(a, pos) {
    a.placement.displayed = pos ? { importance: pos.x, urgency: pos.y } : null;
  }

  /** 좌표를 정수·범위 안으로 맞춘다. 목표 연결 행동은 x>=50(오른쪽 절반)만 허용(§4.10). 점이 가장자리에서 잘리지 않게 2~98로 둔다. */
  function clampPos(a, x, y) {
    const minX = isGoal(a) ? 50 : 2;
    return {
      x: Math.round(Math.max(minX, Math.min(98, x))),
      y: Math.round(Math.max(2, Math.min(98, y))),
    };
  }

  // ---------- 그리기 ----------
  function describe(a, index) {
    const pos = displayed(a);
    const place = pos ? CELLS[cellOf(pos.x, pos.y)].label : "트레이";
    return `${index + 1}. ${a.title} · ${pathText(a) || "그 밖의 할 일"} · ${place} · ${placementText(a)}`;
  }

  // 선택·호버한 점의 상세 문구를 아래 영역에 보여 준다.
  function showDetail(a, index) {
    detail.textContent = describe(a, index) + (displayed(a) ? "" : " · 점을 사분면으로 끌거나 Enter 키를 눌러 배치하세요.");
  }

  // 상태별 개수와 버튼 활성 여부를 갱신한다. 목표 연결 행동을 선택했다면 왼쪽 칸에 aria-disabled를 건다.
  function updateCounts() {
    const list = items();
    const unclassified = list.filter((a) => stateOf(a) === "UNCLASSIFIED").length;
    const pending = list.filter((a) => UNCONFIRMED.has(stateOf(a))).length;
    const confirmed = list.filter((a) => stateOf(a) === "CONFIRMED").length;
    counts.textContent = `미분류 ${unclassified}개 · 확정 대기 ${pending}개 · 확정 ${confirmed}개`;
    cancelButton.disabled = pending === 0; // 취소할 변경이 없으면 비활성(화면 흐름 문서)
    const goalSelected = selected() && isGoal(selected());
    host.querySelectorAll(".quad-cell").forEach((cell) => {
      const leftSide = cell.dataset.cell === "Q2" || cell.dataset.cell === "Q3";
      cell.setAttribute("aria-disabled", leftSide && goalSelected ? "true" : "false");
    });
  }

  // 점을 좌표 평면에 놓는다. CSS의 top은 위가 0이므로 긴급도 y는 100-y로 뒤집는다.
  function positionPoint(node, pos) {
    node.style.left = `${pos.x}%`;
    node.style.top = `${100 - pos.y}%`;
  }

  // 행동 하나의 점 버튼을 만든다. 미분류·미확정(확정 대기)·완료·선택 상태에 따라 CSS 클래스를 붙인다.
  function buildPoint(a, index) {
    const pos = displayed(a);
    const point = el(
      "button",
      {
        type: "button",
        class: "plot-point",
        "data-number": String(index + 1),
        "data-id": String(a.id),
        testid: `point-${a.id}`,
        "aria-label": `${describe(a, index)}. 방향키로 위치 조정, Delete 키로 트레이로 보내기`,
        "aria-pressed": a.id === selectedId ? "true" : "false",
      },
      el("span", { class: "point-label", text: a.title }),
    );
    if (!pos) point.classList.add("unclassified");
    if (UNCONFIRMED.has(stateOf(a))) point.classList.add("pending");
    if (a.isCompleted) point.classList.add("done");
    if (a.id === selectedId) point.classList.add("selected");
    if (pos) positionPoint(point, pos);
    wirePoint(point, a, index);
    return point;
  }

  // 트레이와 사분면을 통째로 다시 그린다. 다시 그리면 포커스가 사라지므로 직전에 포커스가 있던 점을 복원한다.
  function renderPlot() {
    const focusId = document.activeElement?.dataset?.id;
    tray.replaceChildren();
    quadrants.querySelectorAll(".plot-point, .plot-ghost").forEach((node) => node.remove());
    items().forEach((a, index) => {
      const pos = displayed(a);
      (pos ? quadrants : tray).append(buildPoint(a, index));
      const c = a.placement?.confirmed;
      if (c && (stateOf(a) === "RECLASSIFYING" || stateOf(a) === "PENDING_UNCLASSIFY")) {
        const ghost = el("span", { class: "plot-ghost", "aria-hidden": "true", title: `${a.title} · 확정 위치` });
        positionPoint(ghost, { x: c.importance, y: c.urgency });
        quadrants.append(ghost);
      }
    });
    updateCounts();
    if (focusId) host.querySelector(`.plot-point[data-id="${focusId}"]`)?.focus();
  }

  /** 대시보드가 행동 목록을 새로 받을 때 부른다. */
  function update() {
    if (selectedId !== null && !selected()) selectedId = null;
    // 오늘 이미 확정했으면 안내 문구를 숨긴다(화면 흐름 문서: 확정한 뒤 같은 날에는 숨긴다).
    prompt.hidden = Boolean(state.data?.classification?.confirmedToday);
    prompt.textContent =
      "점을 끌어 중요도와 긴급도를 정해 보세요. 목표에 연결된 일은 오른쪽에만 놓을 수 있어요. 확정한 위치는 다시 확정하기 전까지 유지되고, 변경 취소로 되돌릴 수 있어요.";
    renderPlot();
  }

  // ---------- 서버 저장 ----------
  function enqueue(id, task) {
    const previous = saving.get(id) ?? Promise.resolve();
    const next = previous.then(task).finally(() => {
      if (saving.get(id) === next) saving.delete(id);
    });
    saving.set(id, next);
    return next;
  }

  // 사용자가 점을 움직일 때마다 행동별 조작 번호를 올리고, 그 시점의 좌표(null이면 트레이)를 함께 기록한다.
  // 저장 큐는 "실행 시점의 화면 값"이 아니라 "조작 시점에 잡아 둔 좌표"를 보내야 빠른 연속 이동에서도
  // 중간 위치가 빠지지 않고, 응답이 늦게 와도 번호로 "이게 마지막 조작인가"를 판단할 수 있다.
  const opCounter = new Map(); // 행동 id → 마지막 조작 번호
  const lastPos = new Map(); // 행동 id → 마지막 조작의 좌표({x,y} 또는 null=트레이)

  function recordMove(a, pos) {
    const n = (opCounter.get(a.id) ?? 0) + 1;
    opCounter.set(a.id, n);
    lastPos.set(a.id, pos);
    return n;
  }

  /** 조작 시점에 잡아 둔 위치(pos: {x,y} 또는 null)를 서버에 저장한다. 같은 행동의 요청은 순서대로 보낸다. */
  // 서버에 저장하고, 마지막 조작일 때만 화면을 응답으로 갱신한다(opNo 비교).
  function commit(a, opNo, pos) {
    clearTimeout(timers.get(a.id));
    timers.delete(a.id);
    return enqueue(a.id, async () => {
      try {
        const result = await api.put(`/api/actions/${a.id}/placement`, {
          body: { importance: pos ? pos.x : null, urgency: pos ? pos.y : null },
        });
        a.placement = result.placement;
        if (opNo === opCounter.get(a.id)) {
          renderPlot();
          refreshList();
        } else {
          // 응답을 기다리는 사이 더 최근에 움직였다면, 오래된 응답이 화면 위치를 덮어쓰지 못하게 마지막 조작 위치를 유지한다.
          setDisplayed(a, lastPos.get(a.id));
        }
      } catch (error) {
        // 422 QUADRANT_OUT_OF_RANGE 등: 서버 message를 보여 준다. 더 최근 조작이 뒤따르면 그쪽이 마지막 값을 저장하므로 되돌리지 않는다.
        handleApiError(error);
        if (opNo === opCounter.get(a.id)) await reload();
      }
    });
  }

  /** 방향키처럼 연속 입력: 위치는 조작마다 기록하고 전송만 모은다. */
  function schedule(a, pos) {
    recordMove(a, pos);
    clearTimeout(timers.get(a.id));
    timers.set(
      a.id,
      setTimeout(() => commit(a, opCounter.get(a.id), lastPos.get(a.id)), SAVE_DELAY_MS),
    );
  }

  // 디바운스로 대기 중인 이동을 즉시 보내고 진행 중인 저장이 끝나길 기다린다(확정 직전에 호출).
  async function flushAll() {
    for (const a of items()) if (timers.has(a.id)) commit(a, opCounter.get(a.id), lastPos.get(a.id));
    await Promise.all([...saving.values()]);
  }

  // ---------- 동작 ----------
  // 점을 좌표에 놓는다(드래그 종료·칸 클릭·키보드 공통). 좌표를 보정하고 즉시 화면에 반영한 뒤 저장한다. 확정은 별도 버튼이다.
  function placeAt(a, x, y) {
    const wasLeft = isGoal(a) && x < 50;
    const pos = clampPos(a, x, y);
    setDisplayed(a, pos);
    renderPlot();
    say(wasLeft ? GOAL_LEFT_MESSAGE : "위치가 바뀌었어요. 확정하면 저장돼요.");
    commit(a, recordMove(a, pos), pos);
  }

  // 점을 트레이(미분류)로 돌려보낸다. 서버에는 importance·urgency를 null로 보낸다.
  function sendToTray(a) {
    setDisplayed(a, null);
    renderPlot();
    say("트레이로 보냈어요. 확정하면 저장돼요.");
    commit(a, recordMove(a, null), null);
  }

  // 선택한 점을 칸의 중심에 놓는다. 목표 연결 행동은 왼쪽 칸(Q2·Q3)에 놓을 수 없다.
  function placeSelectedInCell(key) {
    const a = selected();
    if (!a) return say("먼저 점을 선택해 주세요.");
    if (isGoal(a) && (key === "Q2" || key === "Q3")) return say(GOAL_LEFT_MESSAGE);
    placeAt(a, ...CELLS[key].center);
  }

  // 점 선택 표시를 갱신한다(점·목록 행·상세 문구·개수).
  function select(a, index, point) {
    selectedId = a.id;
    host.querySelectorAll(".plot-point").forEach((p) => {
      p.classList.toggle("selected", p === point);
      p.setAttribute("aria-pressed", p === point ? "true" : "false");
    });
    document
      .querySelectorAll(".action-row")
      .forEach((row) => row.classList.toggle("selected", row.dataset.actionId === String(a.id)));
    showDetail(a, index);
    updateCounts();
  }

  // [오늘 분류 확정]: 대기 중인 이동을 먼저 저장한 뒤 POST .../placements/confirm으로 미확정 위치를 확정 좌표로 바꾼다.
  async function confirmAll() {
    confirmButton.disabled = true;
    try {
      await flushAll(); // 아직 보내지 않은 이동을 먼저 저장해야 확정 대상에 들어간다
      const result = await api.post("/api/actions/placements/confirm");
      await reload();
      say(
        `${result.confirmedCount}개를 확정했어요.${result.unclassifiedCount ? ` ${result.unclassifiedCount}개는 미분류가 됐어요.` : ""}`,
      );
    } catch (error) {
      handleApiError(error);
    } finally {
      confirmButton.disabled = false;
    }
  }

  /**
   * 확정하지 않은 변경을 모두 취소한다: DELETE /api/actions/placements/pending 한 번(API §4.10, 응답 {date, cancelledCount}).
   * 취소는 "미확정 위치를 버리는 것"이므로 디바운스로 대기 중인 이동은 보내지 않고 버린다(확정과 달리 flush하지 않는다).
   * 이미 전송 중인 저장은 끝날 때까지 기다린 뒤 취소를 보낸다. 기다리지 않으면 늦게 도착한 저장이
   * 취소 뒤에 미확정 변경을 다시 만들어 "취소했는데 남아 있는" 상태가 된다.
   */
  async function cancelAll() {
    cancelButton.disabled = true;
    try {
      for (const timer of timers.values()) clearTimeout(timer);
      timers.clear();
      await Promise.all([...saving.values()]);
      const result = await api.delete("/api/actions/placements/pending");
      await reload();
      say(result?.cancelledCount ? "확정하지 않은 변경을 취소했어요." : "취소할 변경이 없었어요.");
    } catch (error) {
      handleApiError(error);
      await reload();
    }
  }

  // ---------- 점 조작 ----------
  // 포인터 좌표가 요소의 사각형 안인지. 드래그 놓는 곳이 트레이인지 판정하는 데 쓴다.
  function within(node, event) {
    const r = node.getBoundingClientRect();
    return event.clientX >= r.left && event.clientX <= r.right && event.clientY >= r.top && event.clientY <= r.bottom;
  }

  // 점 한 개의 입력 처리를 연결한다: 포커스·호버 상세, 클릭 선택, 키보드 이동, 포인터 드래그.
  function wirePoint(point, a, index) {
    point.addEventListener("focus", () => showDetail(a, index));
    point.addEventListener("pointerenter", () => showDetail(a, index));
    point.addEventListener("click", () => {
      if (dragged) return;
      select(a, index, point);
    });

    // 키보드: 방향키 이동, Enter/Space(트레이의 점 배치), Delete(트레이로)
    point.addEventListener("keydown", (event) => {
      const step = event.shiftKey ? 10 : 1;
      const delta = { ArrowLeft: [-step, 0], ArrowRight: [step, 0], ArrowUp: [0, step], ArrowDown: [0, -step] }[event.key];
      const pos = displayed(a);
      if (!pos) {
        if (delta || event.key === "Enter" || event.key === " ") {
          event.preventDefault();
          // 되돌리기 대기 중이면 확정 위치로, 아니면 기본 위치(목표 연결 행동은 오른쪽)에 놓는다.
          const back = a.placement?.confirmed;
          selectedId = a.id;
          placeAt(a, back ? back.importance : isGoal(a) ? 65 : 25, back ? back.urgency : 50);
        }
        return;
      }
      if (event.key === "Delete" || event.key === "Backspace") {
        event.preventDefault();
        sendToTray(a);
        return;
      }
      if (!delta) return;
      event.preventDefault();
      const next = clampPos(a, pos.x + delta[0], pos.y + delta[1]);
      setDisplayed(a, next);
      positionPoint(point, next); // 다시 그리면 포커스가 사라지므로 점만 옮기고 저장은 모아서 한다
      say(isGoal(a) && pos.x + delta[0] < 50 ? GOAL_LEFT_MESSAGE : "위치가 바뀌었어요. 확정하면 저장돼요.");
      showDetail(a, index);
      schedule(a, next);
    });

    // 포인터 드래그: 5px 넘게 움직여야 드래그로 본다(그 아래는 클릭 = 선택)
    let drag = null;
    point.addEventListener("pointerdown", (event) => {
      if (event.button !== 0 || drag) return;
      dragged = false;
      drag = { id: event.pointerId, x0: event.clientX, y0: event.clientY, moved: false };
      point.setPointerCapture(event.pointerId);
    });
    point.addEventListener("pointermove", (event) => {
      if (!drag || drag.id !== event.pointerId) return;
      if (!drag.moved && Math.hypot(event.clientX - drag.x0, event.clientY - drag.y0) < 5) return;
      drag.moved = true;
      dragged = true;
      // 드래그 중에는 포인터를 따라 화면에 고정(fixed)으로 띄우고, 놓는 위치로 사분면·트레이를 판정한다.
      point.classList.add("dragging");
      point.style.left = `${event.clientX}px`;
      point.style.top = `${event.clientY}px`;
      tray.classList.toggle("drop-target", within(tray, event));
    });
    point.addEventListener("pointerup", (event) => {
      if (!drag || drag.id !== event.pointerId) return;
      const { moved } = drag;
      drag = null;
      tray.classList.remove("drop-target");
      if (!moved) return;
      setTimeout(() => (dragged = false), 0); // 뒤따르는 click을 건너뛴 뒤 해제
      selectedId = a.id;
      const rect = quadrants.getBoundingClientRect();
      if (within(quadrants, event)) {
        placeAt(
          a,
          (100 * (event.clientX - rect.left)) / rect.width,
          100 - (100 * (event.clientY - rect.top)) / rect.height,
        );
      } else if (within(tray, event)) {
        sendToTray(a);
      } else {
        renderPlot(); // 엉뚱한 곳에 놓으면 원래 자리로
      }
    });
    point.addEventListener("pointercancel", (event) => {
      if (drag?.id !== event.pointerId) return;
      drag = null;
      renderPlot();
    });
  }

  /** 화면을 떠날 때: 디바운스 중이던 마지막 이동은 버리지 않고 바로 보낸다(조작 시점에 잡아 둔 좌표라 화면 없이도 보낼 수 있다). */
  function destroy() {
    for (const a of items()) if (timers.has(a.id)) commit(a, opCounter.get(a.id), lastPos.get(a.id));
    for (const timer of timers.values()) clearTimeout(timer);
    timers.clear();
  }

  return { update, destroy, flushAll };
}
