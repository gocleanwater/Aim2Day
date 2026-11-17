"""단위 4 E2E: 행동 생성·완료·수정·삭제와 진행률 (실제 MySQL aim2day_review, goal-service 인스턴스 2대)"""
from datetime import date, timedelta
from decimal import Decimal
from e2elib import *

A = new_user("a")
B = new_user("b")
isolation_check(A)
G1, G2 = G
Y, M, W, ACT = "/api/goals/yearly", "/api/goals/monthly", "/api/goals/weekly", "/api/actions"


def dec(x):
    return Decimal(str(x)).quantize(Decimal("0.01"))


def cur(wid):
    return dec(sql("SELECT current_value FROM weekly_goals WHERE id=%d" % wid))


def rate(table, gid):
    return dec(sql("SELECT progress_rate FROM %s WHERE id=%d" % (table, gid)))


def complete(base, aid, done=True):
    return A.put(base + ACT + "/%d/completion" % aid, {"completed": done})


settings = A.get(G1 + "/api/settings").data
today = settings["today"]
week_end = settings["currentWeek"]["endDate"]
tomorrow = (date.fromisoformat(today) + timedelta(days=1)).isoformat()
after_week = (date.fromisoformat(week_end) + timedelta(days=1)).isoformat()

print("[1] 목표 준비: 수치형 주간 2개(목표 10), 체크형 주간 1개")
yg = A.post(G1 + Y, {"title": "백엔드", "color": "#10B981"}).data
mg = A.post(G2 + M, {"yearlyGoalId": yg["id"], "title": "알고리즘"}).data
wn = A.post(G1 + W, {"monthlyGoalId": mg["id"], "title": "문제 풀기", "measurementType": "NUMERIC", "targetValue": 10}).data
wn2 = A.post(G2 + W, {"monthlyGoalId": mg["id"], "title": "정리", "measurementType": "NUMERIC", "targetValue": 10}).data
wc = A.post(G1 + W, {"monthlyGoalId": mg["id"], "title": "체크형"}).data
ok(all(x and "id" in x for x in (yg, mg, wn, wn2, wc)), "goals created")

print("[2] 생성 규칙")
r = A.post(G1 + ACT, {"type": "GOAL", "monthlyGoalId": mg["id"], "title": "x", "performedDate": today})
ok(r.status == 422 and r.code == "ACTION_WEEKLY_GOAL_AMBIGUOUS" and len(r.details["candidates"] if "candidates" in (r.details or {}) else r.details or []) >= 3, "monthly only with 3 weeklies -> AMBIGUOUS", r)
ok(A.post(G2 + ACT, {"type": "GOAL", "title": "x", "performedDate": today}).status == 400, "GOAL without goal ids 400")
r = A.post(G1 + ACT, {"type": "GOAL", "weeklyGoalId": wn["id"], "title": "x", "performedDate": after_week})
ok(r.status == 422 and r.code == "ACTION_DATE_OUT_OF_RANGE", "GOAL after current week 422", r)
r = A.post(G2 + ACT, {"type": "GOAL", "weeklyGoalId": wc["id"], "title": "x", "performedDate": today, "contributionValue": 1})
ok(r.status == 422 and r.code == "GOAL_NOT_NUMERIC", "contribution on checklist 422", r)
r = B.post(G1 + ACT, {"type": "GOAL", "weeklyGoalId": wn["id"], "title": "x", "performedDate": today})
ok(r.status == 422 and r.code == "GOAL_PARENT_UNAVAILABLE", "other user's weekly 422", r)
r = A.post(G2 + ACT, {"type": "MAINTENANCE", "title": "장보기", "performedDate": tomorrow})
ok(r.status == 201 and r.data["goalPath"] is None and r.data["displayColor"] == "#9CA3AF", "future MAINTENANCE allowed, grey", r)
maint = r.data
r = A.post(G1 + ACT, {"type": "MAINTENANCE", "weeklyGoalId": wn["id"], "title": "x", "performedDate": today})
ok(r.status == 400, "MAINTENANCE with weeklyGoalId 400", r)


def act(base, wid, title, contrib=None):
    body = {"type": "GOAL", "weeklyGoalId": wid, "title": title, "performedDate": today}
    if contrib is not None:
        body["contributionValue"] = contrib
    r = A.post(base + ACT, body)
    assert r.status == 201, r
    return r.data


a1 = act(G1, wn["id"], "문제 2개", 2)
ok(B.put(G2 + ACT + "/%d/completion" % a1["id"], {"completed": True}).status == 404, "other user's action 404")

print("[3] 같은 행동을 두 인스턴스가 동시에 10번 완료 → 기록 1행, current_value +2 한 번")
res = parallel([lambda u=u: complete(u, a1["id"]) for u in (G1, G2) * 5])
ok(all(x.status == 200 for x in res), "all completions 200", sorted(set(x.status for x in res)))
ok(sql("SELECT COUNT(*) FROM weekly_goal_numeric_records WHERE action_id=%d" % a1["id"]) == "1", "exactly 1 record")
ok(cur(wn["id"]) == dec(2) and rate("weekly_goals", wn["id"]) == dec(20), "wn current 2.00, rate 20.00", cur(wn["id"]))

print("[4] 서로 다른 행동 동시 완료 → 기여량 합 정확, 체크형 분자 정확, 월간 = 가중 평균")
a2 = act(G2, wn2["id"], "읽기 1", 1)
a3 = act(G1, wn2["id"], "읽기 4", 4)
c1, c2, c3 = act(G1, wc["id"], "c1"), act(G2, wc["id"], "c2"), act(G1, wc["id"], "c3")
ok(rate("weekly_goals", wc["id"]) == dec(0), "checklist 0/3 -> 0.00")
res = parallel([lambda: complete(G1, a2["id"]), lambda: complete(G2, a3["id"]),
                lambda: complete(G1, c1["id"]), lambda: complete(G2, c2["id"])])
ok(all(x.status == 200 for x in res), "4 concurrent completions 200", [x.status for x in res])
ok(cur(wn2["id"]) == dec(5) and rate("weekly_goals", wn2["id"]) == dec(50), "wn2 1+4 = 5.00, rate 50.00", cur(wn2["id"]))
ok(rate("weekly_goals", wc["id"]) == dec("66.67"), "checklist 2/3 = 66.67", rate("weekly_goals", wc["id"]))
rows = [l.split() for l in sql("SELECT progress_rate, weight FROM weekly_goals WHERE monthly_goal_id=%d AND deleted_at IS NULL" % mg["id"]).splitlines()]
want = (sum(dec(r_) * dec(w_) for r_, w_ in rows) / sum(dec(w_) for _, w_ in rows)).quantize(Decimal("0.01"))
ok(rate("monthly_goals", mg["id"]) == want, "monthly = weighted average (%s)" % want, rate("monthly_goals", mg["id"]))
r = complete(G2, a2["id"])
ok(r.status == 200 and r.data["weeklyGoal"]["currentValue"] is not None and dec(r.data["weeklyGoal"]["currentValue"]) == dec(5), "repeat completion is no-op, response shows 5", r)

print("[5] 완료↔취소 20회(두 인스턴스 번갈아) → current_value 복귀, 진행 기록 이전·다음 값 연속")
bad = 0
for n in range(20):
    r = complete((G1, G2)[n % 2], a1["id"], n % 2 == 1)
    exp = dec(2) if n % 2 == 1 else dec(0)
    if r.status != 200 or cur(wn["id"]) != exp:
        bad += 1
        print("   toggle", n, r.status, cur(wn["id"]))
ok(bad == 0, "20 toggles each 200 with expected current_value", bad)
ok(cur(wn["id"]) == dec(2) and sql("SELECT COUNT(*) FROM weekly_goal_numeric_records WHERE action_id=%d" % a1["id"]) == "1", "ends completed: 2.00, 1 record")
logs = [l.split() for l in sql("SELECT previous_rate, new_rate FROM goal_progress_logs WHERE target_type='WEEKLY_GOAL' AND target_id=%d ORDER BY id" % wn["id"]).splitlines()]
ok(len(logs) >= 21 and all(dec(logs[i][0]) == dec(logs[i - 1][1]) for i in range(1, len(logs))), "weekly log chain continuous (%d logs)" % len(logs), logs[-4:])

print("[6] 완료↔취소 동시 경합 10라운드 → 완료 상태와 기록·current_value가 항상 일치")
bad = 0
for n in range(10):
    res = parallel([lambda: complete(G1, a1["id"], True), lambda: complete(G2, a1["id"], False)])
    if any(x.status != 200 for x in res):
        bad += 1
        print("   round", n, "status", [x.status for x in res])
        continue
    done = sql("SELECT is_completed FROM actions WHERE id=%d" % a1["id"]) == "1"
    recs = sql("SELECT COUNT(*) FROM weekly_goal_numeric_records WHERE action_id=%d" % a1["id"])
    if recs != ("1" if done else "0") or cur(wn["id"]) != (dec(2) if done else dec(0)):
        bad += 1
        print("   round", n, done, recs, cur(wn["id"]))
ok(bad == 0, "completion race: both 200 and record/current_value consistent", bad)
complete(G1, a1["id"], True)

print("[7] 완료된 수치 행동을 다른 주간으로 이동 → 두 주간·월간 진행률")
v = A.get(G1 + ACT + "?date=%s" % today).data
ver = next(x["version"] for x in v["items"] if x["id"] == a1["id"])
mlogs = lambda: sql("SELECT COUNT(*) FROM goal_progress_logs WHERE target_type='MONTHLY_GOAL' AND target_id=%d" % mg["id"])
m_before, m_rate = mlogs(), rate("monthly_goals", mg["id"])
r = A.patch(G2 + ACT + "/%d" % a1["id"], {"weeklyGoalId": wn2["id"], "version": ver})
ok(r.status == 200, "move completed action 200", r)
ok(mlogs() == m_before and rate("monthly_goals", mg["id"]) == m_rate, "same-monthly move: monthly rate unchanged, no monthly log", (m_before, mlogs()))
ok(cur(wn["id"]) == dec(0) and cur(wn2["id"]) == dec(7), "wn 0.00, wn2 7.00", (cur(wn["id"]), cur(wn2["id"])))
ok(sql("SELECT weekly_goal_id FROM weekly_goal_numeric_records WHERE action_id=%d" % a1["id"]) == str(wn2["id"]), "record moved to wn2")
r = A.patch(G1 + ACT + "/%d" % a1["id"], {"contributionValue": 3, "version": r.data["version"]})
ok(r.status == 200 and cur(wn2["id"]) == dec(8), "contribution 2 -> 3 adds diff (8.00)", r)
r2 = A.patch(G2 + ACT + "/%d" % a1["id"], {"title": "stale", "version": ver})
ok(r2.status == 409 and r2.code == "VERSION_CONFLICT" and r2.details.get("currentVersion") == r.data["version"], "stale version 409 with currentVersion", r2)

print("[8] 수행일 경계: 미래 완료 차단")
r = complete(G1, maint["id"])
ok(r.status == 422 and r.code == "ACTION_FUTURE_COMPLETION" and r.details == {"today": today, "performedDate": tomorrow}, "future completion 422", r)

print("[9] 삭제: 미완료는 분모에서 빠지고, 완료는 숨기기만")
ok(A.delete(G2 + ACT + "/%d" % c3["id"]).status == 200, "delete incomplete c3")
ok(rate("weekly_goals", wc["id"]) == dec(100), "checklist 2/2 = 100.00", rate("weekly_goals", wc["id"]))
ok(A.delete(G1 + ACT + "/%d" % c1["id"]).status == 200, "delete completed c1")
ok(rate("weekly_goals", wc["id"]) == dec(100), "checklist stays 100.00 after deleting completed")
lst = A.get(G2 + ACT + "?date=%s" % today).data
ids = [x["id"] for x in lst["items"]]
ok(c1["id"] not in ids and c3["id"] not in ids and c2["id"] in ids, "deleted actions hidden from list", ids)
s = lst["summary"]
ok(s["goal"] == {"total": len([x for x in lst["items"] if x["type"] == "GOAL"]), "completed": len([x for x in lst["items"] if x["type"] == "GOAL" and x["isCompleted"]])}, "summary matches items", s)

print("[10] 직전 주 요약")
r = A.get(G1 + W + "/previous-summary")
ok(r.status == 200, "previous-summary 200", r)

print("[11] 조상 삭제로 숨김 → 행동 쓰기 404")
sql("UPDATE yearly_goals SET deleted_at=UTC_TIMESTAMP() WHERE id=%d" % yg["id"])
ok(complete(G1, a2["id"], False).status == 404, "uncomplete under hidden path 404")
ok(complete(G2, c2["id"], False).status == 404, "checklist under hidden path 404")
lst = A.get(G1 + ACT + "?date=%s" % today).data
ok(all(x["isCompleted"] for x in lst["items"] if x["type"] == "GOAL"), "only completed GOAL actions remain listed", [(x["id"], x["isCompleted"]) for x in lst["items"]])

finish()
