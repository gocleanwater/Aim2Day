// 온보딩 입력 모델(DOM 없음). 화면(views/onboarding.js)과 분리해 두면 규칙만 따로 점검하기 쉽다.
//
// draftData 형식 (서버는 해석하지 않는 자유 JSON, 최대 64KB. API §4.2):
// {
//   seq: 3,                                   // tempId 발급용 증가 번호(지운 tempId를 재사용하지 않기 위함)
//   yearlyGoals: [{ tempId:"y1", title }],
//   monthlyGoals: [{ tempId:"m1", yearlyGoalTempId:"y1" | yearlyGoalId:3, title, description }],
//   weeklyGoals: [{ tempId:"w1", monthlyGoalTempId:"m1" | monthlyGoalId:8, title, measurementType, targetValue }],
//   actions: [{ type:"GOAL"|"MAINTENANCE", monthlyGoalTempId|monthlyGoalId, weeklyGoalTempId|weeklyGoalId,
//               title, performedDate, contributionValue }]
// }
// POST /api/goals/onboarding/complete 본문은 이 형태에서 buildCompleteBody()가 만든다.
// 부모 참조는 "tempId 또는 id 중 하나만" 쓰는 명세 규칙 때문에, 화면에서는 "t:y1" / "id:3" 같은 키 문자열로 다룬다.

export const MAX_DRAFT_BYTES = 64 * 1024;

const LISTS = ["yearlyGoals", "monthlyGoals", "weeklyGoals", "actions"];

export function emptyDraft() {
  return { seq: 0, yearlyGoals: [], monthlyGoals: [], weeklyGoals: [], actions: [] };
}

/** 서버에서 돌아온 draftData(다른 탭·옛 버전이 쓴 값일 수 있음)를 안전한 모양으로 맞춘다. */
export function normalizeDraft(raw) {
  const draft = emptyDraft();
  if (!raw || typeof raw !== "object") return draft;
  draft.seq = Number.isInteger(raw.seq) ? raw.seq : 0;
  for (const name of LISTS) {
    if (Array.isArray(raw[name])) draft[name] = raw[name].filter((item) => item && typeof item === "object");
  }
  return draft;
}

export function draftBytes(draft) {
  return new TextEncoder().encode(JSON.stringify(draft)).length;
}

export function isBlank(item) {
  return !String(item.title ?? "").trim();
}

/** 제목이 빈 행을 없앤다. 이렇게 하면 draft 배열 순서가 곧 complete 본문의 인덱스가 되어, 서버 오류 경로(actions[0].title)를 입력칸에 바로 대응시킬 수 있다. */
export function pruneBlank(draft) {
  for (const name of LISTS) draft[name] = draft[name].filter((item) => !isBlank(item));
}

export function hasContent(draft) {
  return LISTS.some((name) => draft[name].length > 0);
}

export function nextTempId(draft, prefix) {
  draft.seq += 1;
  return `${prefix}${draft.seq}`;
}

// ---------- 부모 참조 키 ----------
/** item의 base 참조("yearlyGoal" 등)를 "t:y1" / "id:3" / "" 로 읽는다. */
export function refKey(item, base) {
  if (item[`${base}TempId`]) return `t:${item[`${base}TempId`]}`;
  if (item[`${base}Id`] !== undefined && item[`${base}Id`] !== null) return `id:${item[`${base}Id`]}`;
  return "";
}

/** 키를 item에 기록한다. tempId와 id를 동시에 두지 않도록 반대쪽은 지운다. */
export function setRef(item, base, key) {
  delete item[`${base}TempId`];
  delete item[`${base}Id`];
  if (key.startsWith("t:")) item[`${base}TempId`] = key.slice(2);
  else if (key.startsWith("id:")) item[`${base}Id`] = Number(key.slice(3));
}

function refForBody(body, base, key) {
  if (key.startsWith("t:")) body[`${base}TempId`] = key.slice(2);
  else if (key.startsWith("id:")) body[`${base}Id`] = Number(key.slice(3));
}

// ---------- 선택지 ----------
// ctx = { existingYearly, existingMonthly, existingWeekly }  (서버에서 읽은 기존 목표)
function suffix(goal) {
  const state = goal.pathStatus || goal.status;
  return state === "ON_HOLD" ? " (보류)" : state === "DROPPED" ? " (중단)" : "";
}

export function yearlyOptions(draft, ctx) {
  return [
    ...ctx.existingYearly.map((g) => ({ key: `id:${g.id}`, label: `${g.title}${suffix(g)} · 기존` })),
    ...draft.yearlyGoals.filter((g) => !isBlank(g)).map((g) => ({ key: `t:${g.tempId}`, label: g.title.trim() })),
  ];
}

export function monthlyOptions(draft, ctx) {
  return [
    ...ctx.existingMonthly.map((g) => ({ key: `id:${g.id}`, label: `${g.title}${suffix(g)} · 기존` })),
    ...draft.monthlyGoals.filter((g) => !isBlank(g)).map((g) => ({ key: `t:${g.tempId}`, label: g.title.trim() })),
  ];
}

/** 특정 월간 목표(키) 아래의 주간 목표 후보. 새로 쓴 것과 이번 주에 이미 있는 것을 합친다. */
export function weeklyOptionsFor(draft, ctx, monthlyKey) {
  if (!monthlyKey) return [];
  const fromDraft = draft.weeklyGoals
    .filter((w) => !isBlank(w) && refKey(w, "monthlyGoal") === monthlyKey)
    .map((w) => ({ key: `t:${w.tempId}`, label: w.title.trim(), numeric: w.measurementType === "NUMERIC" }));
  const fromServer = ctx.existingWeekly
    .filter((w) => `id:${w.path?.monthlyGoal?.id}` === monthlyKey)
    .map((w) => ({ key: `id:${w.id}`, label: `${w.title} · 기존`, numeric: w.measurementType === "NUMERIC" }));
  return [...fromServer, ...fromDraft];
}

/** 행동 행이 가리키는 월간 키. 월간 참조가 없고 주간만 있으면(옛 draft) 주간의 부모에서 찾는다. */
export function actionMonthlyKey(draft, ctx, action) {
  const direct = refKey(action, "monthlyGoal");
  if (direct) return direct;
  const weeklyKey = refKey(action, "weeklyGoal");
  if (!weeklyKey) return "";
  const local = draft.weeklyGoals.find((w) => `t:${w.tempId}` === weeklyKey);
  if (local) return refKey(local, "monthlyGoal");
  const remote = ctx.existingWeekly.find((w) => `id:${w.id}` === weeklyKey);
  return remote?.path?.monthlyGoal?.id ? `id:${remote.path.monthlyGoal.id}` : "";
}

/**
 * 행동이 연결될 주간 목표를 정한다. 명세(§4.9)와 같은 규칙이다:
 * 직접 고른 주간이 후보 안에 있으면 그것, 후보가 하나뿐이면 자동 연결, 그 외(0개·여러 개)는 null.
 */
export function resolveWeekly(draft, ctx, action) {
  const candidates = weeklyOptionsFor(draft, ctx, actionMonthlyKey(draft, ctx, action));
  const chosen = refKey(action, "weeklyGoal");
  if (chosen) {
    const hit = candidates.find((c) => c.key === chosen);
    if (hit) return hit;
  }
  return candidates.length === 1 ? candidates[0] : null;
}

/** 다음 단계에서 부모로 쓸 수 있는 항목이 있는지(= 이 단계를 비워 둔 채 넘어가도 되는지). */
export function hasParentsForStep(draft, ctx, step) {
  if (step === 2) return yearlyOptions(draft, ctx).length > 0;
  if (step === 3) return monthlyOptions(draft, ctx).length > 0;
  return true;
}

/**
 * 앞 단계에서 항목을 지우거나 제목을 비우면 그것을 가리키던 하위 행의 참조가 끊긴다.
 * 하위 행을 지우면 사용자가 쓴 내용이 사라지므로, 참조만 비우고 다시 고르게 한다.
 * (선택지가 하나뿐이면 화면이 자동으로 다시 고른다.)
 */
export function sanitizeRefs(draft, ctx) {
  const yearly = new Set(yearlyOptions(draft, ctx).map((o) => o.key));
  for (const m of draft.monthlyGoals) {
    if (!yearly.has(refKey(m, "yearlyGoal"))) setRef(m, "yearlyGoal", "");
  }
  const monthly = new Set(monthlyOptions(draft, ctx).map((o) => o.key));
  for (const w of draft.weeklyGoals) {
    if (!monthly.has(refKey(w, "monthlyGoal"))) setRef(w, "monthlyGoal", "");
  }
  for (const a of draft.actions) {
    if (a.type !== "GOAL") continue;
    if (!monthly.has(refKey(a, "monthlyGoal"))) setRef(a, "monthlyGoal", "");
    const weeklyKeys = new Set(weeklyOptionsFor(draft, ctx, refKey(a, "monthlyGoal")).map((o) => o.key));
    if (!weeklyKeys.has(refKey(a, "weeklyGoal"))) setRef(a, "weeklyGoal", "");
  }
}

// ---------- complete 본문 ----------
/**
 * 임시 저장을 POST /api/goals/onboarding/complete 본문으로 바꾼다(빈 행은 미리 pruneBlank로 없애 둔다).
 * 서버는 "있는 필드"와 "null인 필드"를 구분해 검사하므로(MAINTENANCE는 연결 필드를 보내면 안 됨) 쓰지 않는 키는 아예 넣지 않는다.
 */
export function buildCompleteBody(draft, ctx) {
  const body = { yearlyGoals: [], monthlyGoals: [], weeklyGoals: [], actions: [] };

  for (const y of draft.yearlyGoals) body.yearlyGoals.push({ tempId: y.tempId, title: y.title.trim() });

  for (const m of draft.monthlyGoals) {
    const item = { tempId: m.tempId, title: m.title.trim() };
    refForBody(item, "yearlyGoal", refKey(m, "yearlyGoal"));
    if (String(m.description ?? "").trim()) item.description = m.description.trim();
    body.monthlyGoals.push(item);
  }

  for (const w of draft.weeklyGoals) {
    const numeric = w.measurementType === "NUMERIC";
    const item = { tempId: w.tempId, title: w.title.trim(), measurementType: numeric ? "NUMERIC" : "CHECKLIST" };
    refForBody(item, "monthlyGoal", refKey(w, "monthlyGoal"));
    if (numeric && w.targetValue !== "" && w.targetValue !== undefined && w.targetValue !== null) {
      item.targetValue = Number(w.targetValue);
    }
    body.weeklyGoals.push(item);
  }

  for (const a of draft.actions) {
    const item = { type: a.type === "MAINTENANCE" ? "MAINTENANCE" : "GOAL", title: a.title.trim() };
    if (a.performedDate) item.performedDate = a.performedDate;
    if (item.type === "GOAL") {
      const weekly = resolveWeekly(draft, ctx, a);
      if (weekly) {
        // 주간이 정해졌으면 주간만 보낸다(월간과 함께 보내면 서버가 둘의 일치까지 검사한다).
        refForBody(item, "weeklyGoal", weekly.key);
        const value = a.contributionValue;
        if (weekly.numeric && value !== "" && value !== undefined && value !== null) {
          item.contributionValue = Number(value);
        }
      } else {
        // 기존 월간 목표에 이번 주 주간 목표가 없거나 여러 개: 서버가 422(REQUIRED/AMBIGUOUS)로 알려 준다.
        refForBody(item, "monthlyGoal", actionMonthlyKey(draft, ctx, a));
      }
    }
    body.actions.push(item);
  }
  return body;
}
