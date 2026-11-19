// 목표 화면들(#/goals, #/goals/{id}, #/plan, #/goals/trash)이 함께 쓰는 도우미.
import { api } from "../api.js";
import { onUserChange } from "../auth.js";
import { confirmDialog, toast } from "../ui.js";

export const DEFAULT_COLOR = "#3B82F6";

export const STATUS_LABELS = { PROGRESS: "진행 중", COMPLETED: "완료", ON_HOLD: "보류", DROPPED: "중단" };
export const LEVEL_LABELS = { YEARLY: "연간", MONTHLY: "월간", WEEKLY: "주간" };

/** 색 입력(<input type=color>)은 소문자 #rrggbb를 주지만 API 예시는 #RRGGBB이므로 대문자로 맞춰 보낸다. */
export function normalizeColor(value) {
  return /^#[0-9a-fA-F]{6}$/.test(value) ? value.toUpperCase() : DEFAULT_COLOR;
}

// ---------- 계정 시간대 ----------
// 서버의 "오늘"·복구 기한은 계정 시간대(GET /api/settings의 timezone, API §4.1) 기준이다.
// 브라우저 시간대로 날짜를 만들면 해외·다른 시간대 설정에서 하루가 어긋나므로 계정 시간대로 표시한다.
let accountTimeZone = null;
let timeZonePromise = null;

// 로그인·로그아웃으로 사용자가 바뀌면 다른 사람의 시간대를 쓰지 않도록 비운다.
onUserChange(() => {
  accountTimeZone = null;
  timeZonePromise = null;
});

/** 설정 화면에서 시간대를 바꾼 직후 캐시를 갱신한다. */
export function setAccountTimeZone(timeZone) {
  accountTimeZone = timeZone || null;
}

/**
 * 계정 시간대를 한 번만 읽어 캐시한다. 실패하면 null로 두고(표시는 브라우저 시간대로 대체) 다음에 다시 시도한다.
 * 날짜 표시용 보조 정보이므로 실패가 화면 전체를 막지 않게 오류를 삼킨다.
 */
export async function loadAccountTimeZone() {
  if (accountTimeZone) return accountTimeZone;
  if (!timeZonePromise) {
    timeZonePromise = api
      .get("/api/settings")
      .then((settings) => {
        accountTimeZone = settings?.timezone || null;
        return accountTimeZone;
      })
      .catch(() => null)
      .finally(() => {
        timeZonePromise = null;
      });
  }
  return timeZonePromise;
}

/**
 * ISO UTC 시각을 YYYY.MM.DD로 표시한다(복구 기한 안내용). 계정 시간대 기준 날짜다.
 * 호출 전에 loadAccountTimeZone()을 기다려 두면 계정 시간대로, 아니면 브라우저 시간대로 표시한다.
 */
export function formatInstantDate(iso) {
  if (!iso) return "";
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return "";
  let parts;
  try {
    // en-CA 형식 대신 formatToParts로 연·월·일을 직접 꺼내 로캘에 따라 모양이 달라지지 않게 한다.
    parts = new Intl.DateTimeFormat("en-US", {
      timeZone: accountTimeZone || undefined,
      year: "numeric",
      month: "2-digit",
      day: "2-digit",
    }).formatToParts(d);
  } catch {
    // 알 수 없는 시간대 이름이면 브라우저 시간대로 대체한다.
    parts = new Intl.DateTimeFormat("en-US", { year: "numeric", month: "2-digit", day: "2-digit" }).formatToParts(d);
  }
  const get = (type) => parts.find((part) => part.type === type)?.value ?? "";
  return `${get("year")}.${get("month")}.${get("day")}`;
}

/**
 * 목표 삭제 공통 흐름(API §4.7). 먼저 확인 없이 삭제를 시도하고,
 * 하위 항목이 있어 409 GOAL_DELETE_CONFIRM_REQUIRED가 오면 details로 확인 모달을 띄운 뒤 ?confirm=true로 다시 보낸다.
 * 서버가 "숨겨질 것이 있는지"를 판단하므로 화면은 하위 개수를 미리 계산하지 않는다.
 * 응답은 화면 이동 뒤에 늦게 도착할 수 있다. 그때 확인 모달을 띄우면 지금 보이는 다른 화면 위에 엉뚱한 모달이 뜨므로,
 * 모달 전·모달 뒤(재전송 전)·삭제 뒤(토스트·후속 렌더링 전)마다 isActive()를 확인하고 비활성이면 null로 조용히 끝낸다.
 * (이미 보낸 첫 삭제 요청이 성공한 경우는 서버에서 삭제됐지만, 화면이 없으므로 안내만 생략한다.)
 * @param {"yearly"|"monthly"|"weekly"} level
 * @param {() => boolean} [isActive] 호출한 화면이 아직 보이는 중인지(route.isActive). 생략하면 항상 true.
 * @returns {Promise<object|null>} 삭제됐으면 응답({deletedAt, restoreDeadline}), 취소했거나 화면을 떠났으면 null. 그 밖의 오류는 던진다.
 */
export async function deleteGoalFlow(level, id, name, isActive = () => true) {
  const path = `/api/goals/${level}/${id}`;
  await loadAccountTimeZone(); // 복구 기한을 계정 시간대 날짜로 보여 주기 위해 삭제 전에 읽어 둔다
  let result;
  try {
    result = await api.delete(path);
  } catch (error) {
    if (error.code !== "GOAL_DELETE_CONFIRM_REQUIRED") throw error;
    if (!isActive()) return null;
    const d = error.details ?? {};
    const counts = [
      d.monthlyGoalCount ? `하위 월간 목표 ${d.monthlyGoalCount}개` : null,
      d.weeklyGoalCount ? `주간 목표 ${d.weeklyGoalCount}개` : null,
      d.incompleteActionCount ? `미완료 행동 ${d.incompleteActionCount}개` : null,
    ].filter(Boolean);
    const lines = [error.message];
    if (counts.length) lines.push(counts.join(" · ") + "가 함께 숨겨져요.");
    if (d.restoreDeadline) lines.push(`${formatInstantDate(d.restoreDeadline)}까지 삭제된 목표에서 복원할 수 있어요.`);
    if (d.notice) lines.push(d.notice);
    const ok = await confirmDialog({
      title: `"${name}"을(를) 삭제할까요?`,
      message: lines.join("\n"),
      confirmText: "삭제",
    });
    if (!ok || !isActive()) return null; // 모달이 열린 동안 화면을 떠났다면 확인 삭제를 보내지 않는다
    result = await api.delete(path, { query: { confirm: true } });
  }
  if (!isActive()) return null;
  toast(
    result?.restoreDeadline
      ? `삭제했어요. ${formatInstantDate(result.restoreDeadline)}까지 복원할 수 있어요.`
      : "삭제했어요.",
  );
  return result;
}
