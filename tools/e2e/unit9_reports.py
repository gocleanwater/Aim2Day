"""단위 9 E2E: 리포트 탭·조회·직접 회고 (실제 MySQL aim2day_review, goal-service 인스턴스 2대)"""
from datetime import date, timedelta
from e2elib import *

A = new_user("r")
B = new_user("s")
isolation_check(A)
G1, G2 = G
Y, M, W, ACT = "/api/goals/yearly", "/api/goals/monthly", "/api/goals/weekly", "/api/actions"
today = date.fromisoformat(A.get(G1 + "/api/settings").data["today"])
month = today.strftime("%Y-%m")
first = today.replace(day=1)
prev_last = first - timedelta(days=1)
mmdd = today.strftime("%m.%d")


def report(c, base, ptype="MONTHLY", period=month):
    return c.get(base + "/api/reports?periodType=%s&period=%s" % (ptype, period))


def next_month(d):
    return (d.replace(day=28) + timedelta(days=4)).replace(day=1)


print("[1] 탭: 이번 달 월간(분기·연간은 3·6·9·12월만), 미래 달 422, 형식 오류 400")
t = A.get(G1 + "/api/reports/tabs").data
expect = ["MONTHLY"]  # 이번 달은 진행 중이라 분기·연간 탭은 끝난 뒤에만 보인다
ok(t["month"] == month and [x["periodType"] for x in t["tabs"]] == expect, "current month: monthly tab only", t)
r = A.get(G2 + "/api/reports/tabs?month=%s" % next_month(today).strftime("%Y-%m"))
ok(r.status == 422 and r.code == "REPORT_INVALID_PERIOD", "future month 422", r)
ok(A.get(G1 + "/api/reports/tabs?month=2026-13").status == 400, "bad month 400")
r = A.get(G2 + "/api/reports/tabs?month=%d-12" % (today.year - 1)).data
ok([x["period"] for x in r["tabs"]] == ["%d-12" % (today.year - 1), "%d-Q4" % (today.year - 1), "%d" % (today.year - 1)], "december has 3 tabs", r)

print("[2] 집계: 완료 GOAL만(지운 완료 행동 포함), MAINTENANCE·미완료·다른 사용자 제외")
y1 = A.post(G1 + Y, {"title": "백엔드", "color": "#3B82F6"}).data
y2 = A.post(G2 + Y, {"title": "건강", "color": "#10B981"}).data
m1 = A.post(G1 + M, {"yearlyGoalId": y1["id"], "title": "알고리즘"}).data
w1 = A.post(G2 + W, {"monthlyGoalId": m1["id"], "title": "문제 풀기"}).data
mk = lambda c, body: c.post(G1 + ACT, body).data
a1 = mk(A, {"type": "GOAL", "weeklyGoalId": w1["id"], "title": "2문제", "performedDate": today.isoformat()})
a2 = mk(A, {"type": "GOAL", "weeklyGoalId": w1["id"], "title": "지울 완료", "performedDate": today.isoformat()})
a3 = mk(A, {"type": "GOAL", "weeklyGoalId": w1["id"], "title": "미완료", "performedDate": today.isoformat()})
a4 = mk(A, {"type": "MAINTENANCE", "title": "장보기", "performedDate": today.isoformat()})
v = A.patch(G2 + ACT + "/%d" % a1["id"], {"resultSummary": "풀이 정리", "version": a1["version"]})
ok(v.status == 200, "result summary saved", v)
for a in (a1, a2, a4):
    A.put(G1 + ACT + "/%d/completion" % a["id"], {"completed": True})
ok(A.delete(G2 + ACT + "/%d" % a2["id"]).status == 200, "completed action deleted (hidden from list)")
rep = report(A, G1).data
ids = [x["id"] for x in rep["actions"]]
ok(sorted(ids) == sorted([a1["id"], a2["id"]]) and rep["summary"] == {"completedActionCount": 2, "activeDayCount": 1}, "aggregation includes deleted completed, excludes others", rep["summary"])
ok(rep["results"] == [{"actionId": a1["id"], "performedDate": today.isoformat(), "resultSummary": "풀이 정리"}], "results only non-empty summary", rep["results"])
gp = next(x for x in rep["actions"] if x["id"] == a1["id"])["goalPath"]
ok(gp["yearlyGoal"]["title"] == "백엔드" and gp["weeklyGoal"]["title"] == "문제 풀기", "goalPath names", gp)
ok(report(B, G2).data["actions"] == [] and report(B, G2).data["summary"] == {"completedActionCount": 0, "activeDayCount": 0}, "other user sees nothing")

print("[3] goalProgress: 미확정 NOT_CONFIGURED, 이번 달 최초 확정 INITIAL_BASELINE → 기록을 지난달로 옮기면 PREVIOUS_PERIOD_END")
C = Y + "/%d/criteria" % y1["id"]
gv = A.get(G1 + C).data["goalVersion"]
r = A.put(G2 + C, {"goalVersion": gv, "items": [{"text": "기준선", "weight": 10}, {"text": "포트폴리오", "weight": 40}, {"text": "코테", "weight": 50}]})
items = {i["text"]: i["id"] for i in r.data["items"]}
A.post(G1 + C + "/confirm", {"goalVersion": r.data["goalVersion"], "baselineAchievedCriterionIds": [items["기준선"]]})
A.put(G2 + C + "/%d/achievement" % items["포트폴리오"], {"achieved": True})
gpro = {x["yearlyGoalId"]: x for x in report(A, G2).data["goalProgress"]}
g1, g2 = gpro[y1["id"]], gpro[y2["id"]]
ok(g2["state"] == "NOT_CONFIGURED" and g2["changePp"] is None, "unconfigured goal", g2)
ok(g1["state"] == "COMPARABLE" and g1["startBase"] == "INITIAL_BASELINE" and float(g1["startRate"]) == 10 and float(g1["endRate"]) == 50
   and float(g1["changePp"]) == 40 and g1["startLabel"] == "기록 시작 (%s)" % mmdd and g1["endLabel"] == "오늘 (%s)" % mmdd, "initial baseline this month", g1)
if today.month > 1:
    base_id = sql("SELECT MIN(id) FROM goal_progress_logs WHERE target_type='YEARLY_GOAL' AND target_id=%d" % y1["id"])
    sql("UPDATE goal_progress_logs SET log_date='%s' WHERE id=%s" % (prev_last.isoformat(), base_id))
    g1 = next(x for x in report(A, G1).data["goalProgress"] if x["yearlyGoalId"] == y1["id"])
    ok(g1["startBase"] == "PREVIOUS_PERIOD_END" and float(g1["startRate"]) == 10 and g1["startLabel"] == "%d월 말" % prev_last.month
       and g1["observedFrom"] == first.isoformat(), "previous period end", g1)
    pm = report(A, G2, "MONTHLY", prev_last.strftime("%Y-%m")).data
    pg = next(x for x in pm["goalProgress"] if x["yearlyGoalId"] == y1["id"])
    ok(pg["state"] == "COMPARABLE" and pg["endLabel"] == "%d월 말" % prev_last.month, "last month report closed label", pg)
gv = A.get(G1 + C).data["goalVersion"]
cur = A.get(G1 + C).data["items"]
A.put(G2 + C, {"goalVersion": gv, "items": [{"id": i["id"], "text": i["text"], "weight": i["weight"]} for i in cur], "memo": "개정"})
g1 = next(x for x in report(A, G1).data["goalProgress"] if x["yearlyGoalId"] == y1["id"])
ok(g1["state"] == "CRITERIA_REVISED" and g1["changePp"] is None, "revision in period -> not comparable", g1)

print("[4] 두 인스턴스가 같은 기간 첫 회고 PUT을 동시에 → 200 하나, 409 하나, 행 1개")
RV = "/api/reports/reviews/MONTHLY/%s" % month
res = parallel([lambda: A.put(G1 + RV, {"improvements": "g1", "nextFocus": "x"}),
                lambda: A.put(G2 + RV, {"improvements": "g2", "nextFocus": "y"})])
st = sorted(x.status for x in res)
ok(st in ([200, 409], [201, 409]) and [x for x in res if x.status == 409][0].code == "VERSION_CONFLICT", "first review PUT race", st)
ok(sql("SELECT COUNT(*) FROM period_reviews WHERE user_id=%d" % A.user_id) == "1", "exactly one review row")
rv = report(A, G2).data["review"]
r = A.put(G1 + RV, {"improvements": "고침", "version": rv["version"]})
ok(r.status == 200 and r.data["nextFocus"] is None and r.data["version"] == rv["version"] + 1, "missing field saved as null", r)
r = A.put(G2 + "/api/reports/reviews/MONTHLY/%s" % next_month(today).strftime("%Y-%m"), {"improvements": "x"})
ok(r.status == 422 and r.code == "REPORT_INVALID_PERIOD", "future period review 422", r)
ok(report(B, G1).data["review"] is None, "other user's review hidden")

finish()
