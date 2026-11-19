// 대시보드의 읽기 중심 영역: 해야 할 일·일정(달력 요약), 이번 주 목표, 이번 달 목표, 최근 진행 기록.
// 각 함수는 서버에서 받은 배열을 받아 host 안을 통째로 다시 그린다(영역별로 따로 불러오므로 한 영역 오류가 다른 영역을 막지 않는다).
import { el, formatDate } from "../ui.js";

export function suffixOf(goal) {
  const state = goal.pathStatus || (goal.status !== "PROGRESS" && goal.status !== "COMPLETED" ? goal.status : null);
  return state === "ON_HOLD" ? " (보류)" : state === "DROPPED" ? " (중단)" : "";
}

/** 진행률 막대. NO_RECORD(계산할 하위 항목 없음)는 막대 대신 "기록 없음"을 보여 준다(API §2.7). */
export function progressView(progress, extraText) {
  if (!progress || progress.state === "NO_RECORD" || progress.rate === null || progress.rate === undefined) {
    return el("p", { class: "action-meta", text: `기록 없음${extraText ? ` · ${extraText}` : ""}` });
  }
  const rate = Math.max(0, Math.min(100, Number(progress.rate)));
  return el(
    "div",
    {},
    el(
      "div",
      { class: "bar", role: "progressbar", "aria-valuemin": "0", "aria-valuemax": "100", "aria-valuenow": String(rate), "aria-label": "진행률" },
      el("div", { class: "bar-fill", style: `width:${rate}%` }),
    ),
    el("p", { class: "action-meta", text: `${progress.rate}%${extraText ? ` · ${extraText}` : ""}` }),
  );
}

// ---------- 달력 요약 ----------
function taskWhen(item) {
  const d = item.daysUntilDue;
  if (d === null || d === undefined) return "";
  if (d < 0) return "기한 지남";
  return d === 0 ? "오늘" : `D-${d}`;
}

function eventWhen(item, today) {
  if (item.startDate <= today && item.endDate >= today) return "진행 중";
  const d = item.daysUntilDue;
  if (d === 0) return "오늘";
  if (d === 1) return "내일";
  return d === null || d === undefined ? "" : `D-${d}`;
}

function calendarRow(item, kind, whenText, onToggle) {
  const check = el("input", {
    type: "checkbox",
    id: `cal-${kind}-${item.id}`,
    "aria-label": `${item.title} 완료`,
    testid: `cal-${kind}-check-${item.id}`,
  });
  check.checked = Boolean(item.isCompleted);
  check.addEventListener("change", () => onToggle(item, check.checked, check));

  const goal = item.yearlyGoal;
  return el(
    "li",
    { class: item.isCompleted ? "cal-row done" : "cal-row", testid: `cal-${kind}-${item.id}` },
    check,
    el(
      "div",
      { class: "cal-text-wrap" },
      el("div", { class: "cal-text" }, goal ? el("span", { class: "cal-dot", style: `background:${goal.color || "#3B82F6"}` }) : null, item.title),
      el("p", { class: "action-meta", text: [whenText, goal ? goal.title : null, item.startDate !== item.endDate ? `${formatDate(item.startDate)} – ${formatDate(item.endDate)}` : formatDate(item.startDate)].filter(Boolean).join(" · ") }),
    ),
  );
}

export function renderCalendarSummary({ tasksHost, eventsHost, pastHost, pastSummary }, data, onToggle) {
  tasksHost.replaceChildren(
    data.tasks.length === 0
      ? el("p", { class: "note", text: "남은 해야 할 일이 없어요." })
      : el("ul", { class: "list-plain" }, data.tasks.map((t) => calendarRow(t, "task", taskWhen(t), onToggle))),
  );
  eventsHost.replaceChildren(
    data.events.length === 0
      ? el("p", { class: "note", text: "7일 안에 일정이 없어요." })
      : el("ul", { class: "list-plain" }, data.events.map((e) => calendarRow(e, "event", eventWhen(e, data.today), onToggle))),
  );
  pastSummary.textContent = `지난 일정 · 최근 7일 (${data.pastEvents.length}개)`;
  pastHost.replaceChildren(
    data.pastEvents.length === 0
      ? el("p", { class: "note", text: "최근 7일 안에 끝난 일정이 없어요." })
      : el(
          "ul",
          { class: "list-plain" },
          data.pastEvents.map((e) => calendarRow(e, "past-event", `D+${e.daysSinceEnd}`, onToggle)),
        ),
  );
}

// ---------- 이번 주·이번 달 ----------
export function renderWeekly(host, goals) {
  if (goals.length === 0) {
    host.replaceChildren(el("p", { class: "note", testid: "weekly-empty", text: "이번 주 목표가 아직 없어요. 이번 주 목표를 설정해 보세요." }));
    return;
  }
  host.replaceChildren(
    ...goals.map((g) => {
      const numeric = g.measurementType === "NUMERIC";
      const extra = numeric
        ? `${g.currentValue} / ${g.targetValue}${g.isTargetReached ? " · 목표 달성" : ""}`
        : `행동 ${g.actionCounts?.completed ?? 0} / ${g.actionCounts?.total ?? 0} 완료`;
      return el(
        "div",
        { class: "plan-card", testid: `weekly-${g.id}` },
        el(
          "div",
          { class: "plan-top" },
          el("span", { text: g.title + suffixOf(g) }),
          el("span", { class: "note", text: `${formatDate(g.startDate)} – ${formatDate(g.endDate)}` }),
        ),
        el("p", { class: "action-meta", text: [g.path?.yearlyGoal?.title, g.path?.monthlyGoal?.title].filter(Boolean).join(" › ") }),
        g.isDelayed ? el("span", { class: "badge warn", text: "기한 지남" }) : null,
        progressView(g.progress, extra),
      );
    }),
  );
}

export function renderMonthly(host, goals) {
  if (goals.length === 0) {
    host.replaceChildren(el("p", { class: "note", testid: "monthly-empty", text: "이번 달 목표가 아직 없어요. 이번 달 목표를 설정해 보세요." }));
    return;
  }
  host.replaceChildren(
    ...goals.map((g) =>
      el(
        "div",
        { class: "plan-card", testid: `monthly-${g.id}` },
        el("p", { class: "action-meta", text: g.yearlyGoal?.title ?? "" }),
        el(
          "div",
          { class: "plan-top" },
          el("span", { text: g.title + suffixOf(g) }),
          el("span", { class: "note", text: `주간 목표 ${g.weeklyGoalCount ?? 0}개` }),
        ),
        g.isDelayed ? el("span", { class: "badge warn", text: "기한 지남" }) : null,
        progressView(g.progress, ""),
      ),
    ),
  );
}

// ---------- 최근 진행 기록 ----------
const TARGET_LABEL = { WEEKLY_GOAL: "주간", MONTHLY_GOAL: "월간", YEARLY_GOAL: "연간" };

export function renderLogs(host, logs) {
  if (logs.length === 0) {
    host.replaceChildren(el("p", { class: "note", text: "아직 진행 기록이 없어요." }));
    return;
  }
  host.replaceChildren(
    el(
      "ul",
      { class: "list-plain" },
      logs.map((log) => {
        const change =
          log.newRate === null || log.newRate === undefined
            ? ""
            : ` ${log.previousRate ?? "-"}% → ${log.newRate}%`;
        return el(
          "li",
          { class: "log-row", testid: `log-${log.id}` },
          el("strong", { text: formatDate(log.logDate) }),
          ` · ${TARGET_LABEL[log.targetType] ?? ""} ${log.targetTitle ?? ""}${change}`,
          log.memo ? el("div", { class: "action-meta", text: log.memo }) : null,
        );
      }),
    ),
  );
}
