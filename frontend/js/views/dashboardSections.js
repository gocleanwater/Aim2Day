// 대시보드의 읽기 중심 영역: 해야 할 일·일정(달력 요약), 이번 주 목표, 이번 달 목표, 최근 진행 기록.
// 각 함수는 서버에서 받은 배열을 받아 host 안을 통째로 다시 그린다(영역별로 따로 불러오므로 한 영역 오류가 다른 영역을 막지 않는다).
import { el, formatDate } from "../ui.js";

/**
 * 목표 제목 뒤에 붙일 상태 표시를 정한다. 반환: " (보류)" | " (중단)" | "".
 * 상태 출처가 두 가지다.
 *  - pathStatus: 주간·월간 목표의 상위 경로(월간·연간)까지 반영한 상태. 있으면 이것을 우선한다.
 *    상위 목표가 보류·중단이면 하위 목표도 같이 흐리게 보여야 하기 때문이다.
 *  - status: 목표 자신의 상태. PROGRESS(진행 중)·COMPLETED(완료)는 꼬리표가 필요 없으므로 null로 치고,
 *    그 밖의 값(ON_HOLD·DROPPED)만 표시 대상으로 삼는다.
 * 분기는 표시 문구가 아니라 상태 코드(ON_HOLD·DROPPED)로 한다.
 */
export function suffixOf(goal) {
  const state = goal.pathStatus || (goal.status !== "PROGRESS" && goal.status !== "COMPLETED" ? goal.status : null);
  return state === "ON_HOLD" ? " (보류)" : state === "DROPPED" ? " (중단)" : "";
}

/**
 * 진행률 막대. NO_RECORD(계산할 하위 항목 없음)는 막대 대신 "기록 없음"을 보여 준다(API §2.7).
 * 입력: progress = {state, rate}(rate는 0~100 숫자 또는 null), extraText = 막대 아래에 덧붙일 문구(없으면 빈 문자열).
 * 서버가 0%(기록은 있으나 아직 0)와 "계산 불가"를 구분해 주므로, rate가 0인 것과 null인 것을 같게 다루면 안 된다.
 * 막대 너비만 0~100으로 보정하고, 글자로 보이는 값은 서버가 준 rate 그대로 쓴다.
 */
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
// 달력 요약 데이터(GET /api/calendar/dashboard)의 모양:
//   { today, tasks[], events[], pastEvents[] }
//   tasks      기한이 있는 해야 할 일. daysUntilDue = 기한까지 남은 일 수(음수면 지남, 기한 없으면 null)
//   events     앞으로 7일 안에 걸쳐 있는 일정. startDate·endDate, daysUntilDue = 시작까지 남은 일 수
//   pastEvents 최근 7일 안에 끝난 일정. daysSinceEnd = 끝난 뒤 지난 일 수
// 공통 항목 필드: id, title, isCompleted, startDate, endDate, yearlyGoal({title, color} 또는 null: 연결된 연간 목표)
// 남은 일 수 계산은 서버가 계정 시간대의 "오늘" 기준으로 끝내 두므로 화면은 문구로만 바꾼다.

/** 해야 할 일의 기한 문구. 기한 없음 "", 지남 "기한 지남", 오늘 "오늘", 그 외 "D-n". */
function taskWhen(item) {
  const d = item.daysUntilDue;
  if (d === null || d === undefined) return "";
  if (d < 0) return "기한 지남";
  return d === 0 ? "오늘" : `D-${d}`;
}

/**
 * 일정의 시점 문구. 오늘이 시작~종료 사이에 들면 daysUntilDue와 무관하게 "진행 중"이다
 * (여러 날 일정은 시작일이 이미 지났어도 아직 진행 중이므로 먼저 검사한다). 그 외에는 시작까지 남은 일 수로 표현한다.
 * 날짜는 YYYY-MM-DD 문자열이라 사전순 비교가 곧 날짜 비교다.
 */
function eventWhen(item, today) {
  if (item.startDate <= today && item.endDate >= today) return "진행 중";
  const d = item.daysUntilDue;
  if (d === 0) return "오늘";
  if (d === 1) return "내일";
  return d === null || d === undefined ? "" : `D-${d}`;
}

/**
 * 해야 할 일·일정·지난 일정이 함께 쓰는 한 줄. kind는 "task"|"event"|"past-event"이며 id·testid가 겹치지 않게 하는 용도다.
 * 체크박스를 바꾸면 onToggle(item, 새 체크값, 체크박스 요소)를 부른다. 실제 API 호출과 실패 시 되돌림은
 * 호출한 쪽(dashboard.js)이 맡는다. 그래야 이 파일이 화면 그리기에만 집중한다.
 * 보조 줄에는 시점 문구 · 연결된 연간 목표 · 기간(하루짜리면 날짜 하나)을 중간점으로 이어 붙인다.
 */
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

/**
 * 달력 요약 세 영역(해야 할 일, 7일 안 일정, 지난 일정)을 다시 그린다.
 * hosts: 각 영역의 컨테이너와 지난 일정 접이식 제목(pastSummary). data: 위 달력 요약 데이터. onToggle: calendarRow 참고.
 * 비어 있으면 목록 대신 안내 문구를 보여 준다.
 */
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
// 주간 목표 항목 필드(GET /api/goals/weekly 등): id, title, measurementType("NUMERIC"이면 수치형, 아니면 행동 기반),
//   startDate·endDate, currentValue·targetValue·isTargetReached(수치형), actionCounts{completed,total}(행동 기반),
//   path{yearlyGoal,monthlyGoal}(상위 경로 제목), isDelayed(기한 지남), progress, status·pathStatus
/**
 * 이번 주 목표 카드 목록. 진행 표시가 측정 방식에 따라 갈린다.
 *  - 수치형: "현재 / 목표" 값과 목표 달성 여부
 *  - 행동 기반: 연결된 오늘 행동의 완료 수 / 전체 수 (actionCounts가 없으면 0 / 0으로 본다)
 * 목표가 없으면 목표 설정으로 이어지는 안내 문구만 보여 준다.
 */
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

/**
 * 이번 달 목표 카드 목록. 월간 목표 필드: id, title, yearlyGoal{title}(상위 연간 목표), weeklyGoalCount, isDelayed, progress, status·pathStatus.
 * 월간 목표의 진행률은 하위 주간 목표에서 서버가 계산하므로 여기서는 별도 문구 없이 막대만 그린다.
 */
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

/**
 * 최근 진행 기록 목록. 기록 필드: id, logDate, targetType(WEEKLY_GOAL|MONTHLY_GOAL|YEARLY_GOAL), targetTitle,
 * previousRate·newRate(진행률 변화), memo.
 * newRate가 없으면 진행률이 바뀌지 않은 기록이므로 "이전% → 새%" 문구를 붙이지 않는다. 이전 값만 없으면 "-"로 표시한다.
 */
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
