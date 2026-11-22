// 삭제된 목표 (#/goals/trash). GET /api/goals/trash, POST /api/goals/{level}/{id}/restore
// 삭제 후 30일 이내 목표를 단계와 관계없이 최근 삭제 순으로 보여 주고 복원한다.
import { api } from "../api.js";
import { el, handleApiError, renderLoading, setBusy, toast } from "../ui.js";
import { LEVEL_LABELS, formatInstantDate, loadAccountTimeZone } from "./goalCommon.js";

// 화면 진입점. 목록(GET /api/goals/trash)은 서버가 복구 기한 안의 목표를 단계 구분 없이 최근 삭제 순으로 준다.
// 항목마다 level(YEARLY|MONTHLY|WEEKLY)을 소문자로 바꿔 복원 경로(/api/goals/{level}/{id}/restore)를 만든다.
export function render(container, route) {
  renderLoading(container);
  const alive = () => route.isActive();

  // 목록과 계정 시간대를 함께 읽어 그린다. 복원·기한 만료 뒤에도 이 함수로 목록을 새로 맞춘다.
  async function load() {
    let items;
    try {
      // 복원 기한 날짜를 계정 시간대로 표시하려고 목록과 함께 시간대를 읽는다(시간대 읽기 실패는 무시된다).
      [items] = await Promise.all([api.get("/api/goals/trash"), loadAccountTimeZone()]);
    } catch (error) {
      if (!alive()) return;
      container.replaceChildren(
        el("h1", { class: "page-title", text: "삭제된 목표" }),
        el("p", { class: "form-error", role: "alert", text: error.message }),
      );
      handleApiError(error);
      return;
    }
    if (!alive()) return;
    container.replaceChildren(
      el(
        "div",
        { class: "dash-head" },
        el("h1", { class: "page-title", style: "margin:0", text: "삭제된 목표" }),
        el("a", { href: "#/goals", class: "btn btn-outline btn-small", text: "닫기", testid: "trash-close" }),
      ),
      el(
        "section",
        { class: "card" },
        el("p", { class: "note", text: "삭제 후 30일 안에는 복원할 수 있어요. 복원하면 하위 목표와 미완료 행동도 원래 연결대로 다시 보여요. 연간 목표는 복원할 때 개수 한도를 다시 확인해요." }),
        el("p", { class: "form-error", "data-testid": "trash-message", role: "alert", hidden: true }),
        items.length === 0
          ? el("p", { class: "note", testid: "trash-empty", text: "삭제된 목표가 없어요." })
          : el("ul", { class: "list-plain", testid: "trash-list" }, items.map(row)),
      ),
    );
  }

  // 삭제된 목표 한 줄. 남은 일수, 복구 기한 날짜, 상위 목표(삭제되어 있으면 먼저 복원하라는 안내)를 보여 준다.
  function row(item) {
    const level = item.level.toLowerCase(); // API 경로와 data-testid에 쓰는 소문자 단계
    const button = el("button", {
      type: "button",
      class: "btn btn-secondary btn-small",
      text: "복원",
      "aria-label": `${item.title} 복원`,
      testid: `restore-${level}-${item.id}`,
      onclick: () => restore(item, button),
    });
    const remain = item.remainingDays === 0 ? "오늘까지 복원 가능" : `복구 ${item.remainingDays}일 남음`;
    return el(
      "li",
      { class: "action-row", testid: `trash-${level}-${item.id}` },
      el(
        "div",
        { class: "action-main" },
        el("div", { class: "action-title", text: item.title }),
        el("p", { class: "action-meta", text: `${LEVEL_LABELS[item.level] ?? item.level} · ${item.periodLabel} · ${remain} (${formatInstantDate(item.restoreDeadline)}까지)` }),
        item.parent ? el("p", { class: "action-meta", text: `상위 목표: ${item.parent.title ?? ""}${item.isParentDeleted ? " (삭제됨 — 상위 목표를 먼저 복원해 주세요)" : ""}` }) : null,
      ),
      el("div", { class: "action-buttons" }, button),
    );
  }

  // 복원 요청. 오류는 code로 분기한다: 연간 목표 한도 초과, 삭제된 상위 목표, 복구 기한 만료는 각각 안내하고 그 밖은 공통 규칙으로 처리한다.
  async function restore(item, button) {
    const message = container.querySelector('[data-testid="trash-message"]');
    message.hidden = true;
    setBusy(button, true, "복원 중…");
    try {
      await api.post(`/api/goals/${item.level.toLowerCase()}/${item.id}/restore`);
      if (!alive()) return; // 응답이 늦어 다른 화면이 열렸다면 토스트·재렌더링을 하지 않는다
      toast(`"${item.title}"을(를) 복원했어요.`);
      await load();
    } catch (error) {
      if (!alive()) return;
      setBusy(button, false);
      switch (error.code) {
        case "GOAL_LIMIT_EXCEEDED": {
          // 연간 목표가 이미 한도: 서버 message에 더해 현재·최대 개수와 가능한 조치를 알려 준다.
          const d = error.details ?? {};
          message.textContent = `${error.message}${d.max ? ` (현재 ${d.current}개 / 최대 ${d.max}개)` : ""} 다른 연간 목표를 삭제한 뒤 복원해 주세요.`;
          message.hidden = false;
          break;
        }
        case "GOAL_PARENT_DELETED":
          message.textContent = `${error.message} 상위 목표를 먼저 복원해 주세요.`;
          message.hidden = false;
          break;
        case "GOAL_RESTORE_EXPIRED":
          // 기한이 지난 항목은 목록에서도 사라져야 하므로 안내 후 다시 불러온다.
          toast(error.message, { error: true });
          await load();
          break;
        default:
          handleApiError(error, { onVersionConflict: load });
      }
    }
  }

  load();
}
