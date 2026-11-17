"""단위 3 E2E: 월간·주간 목표, 진행률 엔진, 수치 기록 (실제 MySQL aim2day_review, goal-service 인스턴스 2대)"""
from decimal import Decimal
from e2elib import *

A = new_user("m")
B = new_user("n")
isolation_check(A)
G1, G2 = G
Y, M, W = "/api/goals/yearly", "/api/goals/monthly", "/api/goals/weekly"


def dec(x):
    return Decimal(str(x)).quantize(Decimal("0.01"))


print("[1] 월간·주간 생성(현재 달·현재 주)")
yg = A.post(G1 + Y, {"title": "백엔드", "color": "#10B981"}).data
r = A.post(G2 + M, {"yearlyGoalId": yg["id"], "title": "알고리즘"})
ok(r.status == 201 and r.data["color"] is None and r.data["displayColor"] == "#10B981" and r.data["progress"]["state"] == "NO_RECORD", "monthly create inherits color, NO_RECORD", r)
mg = r.data
settings = A.get(G1 + "/api/settings").data
ok(mg["startDate"] == settings["currentMonth"]["startDate"], "monthly = current month", mg["startDate"])
r = A.post(G1 + W, {"monthlyGoalId": mg["id"], "title": "문제 풀기", "measurementType": "NUMERIC", "targetValue": 20})
ok(r.status == 201 and r.data["startDate"] == settings["currentWeek"]["startDate"] and r.data["progress"]["state"] == "RECORDED", "numeric weekly = current week, RECORDED", r)
w1 = r.data
r = A.post(G2 + W, {"monthlyGoalId": mg["id"], "title": "정리", "measurementType": "NUMERIC", "targetValue": 10, "weight": 3})
w2 = r.data
r = A.post(G1 + W, {"monthlyGoalId": mg["id"], "title": "체크형"})
ok(r.status == 201 and r.data["progress"] == {"state": "NO_RECORD", "rate": None} and r.data["targetValue"] is None, "checklist weekly NO_RECORD", r)
w3 = r.data

print("[2] 소유권·부모 검사")
ok(B.get(G1 + M + "/%d" % mg["id"]).status == 404, "other user's monthly 404")
ok(B.get(G2 + W + "/%d" % w1["id"]).status == 404, "other user's weekly 404")
r = B.post(G1 + M, {"yearlyGoalId": yg["id"], "title": "x"})
ok(r.status == 422 and r.code == "GOAL_PARENT_UNAVAILABLE" and r.details == {"parentLevel": "YEARLY", "parentId": yg["id"]}, "other user's parent 422", r)
ny = A.post(G1 + Y, {"title": "내년", "targetYear": int(settings["today"][:4]) + 1}).data
r = A.post(G2 + M, {"yearlyGoalId": ny["id"], "title": "x"})
ok(r.status == 422 and r.code == "GOAL_PARENT_PERIOD_MISMATCH", "next-year parent 422 period mismatch", r)
ok(B.post(G2 + W + "/%d/numeric-records" % w1["id"], {"amount": 1}).status == 404, "other user's record 404")

print("[3] 같은 주간에 두 인스턴스로 동시 +1 x20 → current_value 정확히 20")
res = parallel([lambda u=u: A.post(u + W + "/%d/numeric-records" % w1["id"], {"amount": 1}) for u in (G1, G2) * 10])
ok(all(x.status == 201 for x in res), "all 20 records 201", sorted(set(x.status for x in res)))
d = A.get(G2 + W + "/%d" % w1["id"]).data
cur = d.get("currentValue") if "currentValue" in d else d["goal"]["currentValue"] if "goal" in d else None
row = sql("SELECT current_value, progress_rate FROM weekly_goals WHERE id=%d" % w1["id"]).split()
ok(dec(row[0]) == dec(20) and dec(row[1]) == dec(100), "DB current_value 20.00, rate 100.00", row)

print("[4] 같은 월간의 두 주간(둘 다 100% 미만)에 서로 다른 인스턴스로 동시 기록 → 두 진행률 모두 변경, 월간 = 가중 평균")
w4 = A.post(G2 + W, {"monthlyGoalId": mg["id"], "title": "읽기", "measurementType": "NUMERIC", "targetValue": 100}).data
before = {i: dec(sql("SELECT progress_rate FROM weekly_goals WHERE id=%d" % i)) for i in (w2["id"], w4["id"])}
ok(all(v < dec(100) for v in before.values()), "both weeklies start below 100%", before)
res = parallel([lambda: A.post(G1 + W + "/%d/numeric-records" % w2["id"], {"amount": 2}),
                lambda: A.post(G1 + W + "/%d/numeric-records" % w2["id"], {"amount": 3}),
                lambda: A.post(G2 + W + "/%d/numeric-records" % w4["id"], {"amount": 7}),
                lambda: A.post(G2 + W + "/%d/numeric-records" % w1["id"], {"amount": 1})])
ok(all(x.status == 201 for x in res), "concurrent records 201", [x.status for x in res])
after = {i: dec(sql("SELECT progress_rate FROM weekly_goals WHERE id=%d" % i)) for i in (w2["id"], w4["id"])}
ok(after == {w2["id"]: dec(50), w4["id"]: dec(7)}, "w2 50.00 and w4 7.00 both changed", after)
rows = [l.split() for l in sql("SELECT progress_rate, weight FROM weekly_goals WHERE monthly_goal_id=%d AND deleted_at IS NULL" % mg["id"]).splitlines()]
want = (sum(dec(r_) * dec(w_) for r_, w_ in rows) / sum(dec(w_) for _, w_ in rows)).quantize(Decimal("0.01"))
got = dec(sql("SELECT progress_rate FROM monthly_goals WHERE id=%d" % mg["id"]))
ok(got == want, "monthly rate = weighted average of final weekly rates (%s)" % want, got)
ok(dec(sql("SELECT current_value FROM weekly_goals WHERE id=%d" % w2["id"])) == dec(5), "w2 current 5.00")

print("[5] 직접 기록 삭제·오류")
recs = A.get(G1 + W + "/%d/numeric-records" % w2["id"]).data
r = A.delete(G2 + W + "/%d/numeric-records/%d" % (w2["id"], recs[0]["id"]))
ok(r.status == 200, "delete direct record", r)
ok(dec(sql("SELECT current_value FROM weekly_goals WHERE id=%d" % w2["id"])) == dec(5) - dec(recs[0]["amount"]), "current_value subtracted")
ok(A.post(G1 + W + "/%d/numeric-records" % w3["id"], {"amount": 1}).code == "GOAL_NOT_NUMERIC", "checklist record 422")
ok(A.post(G1 + W + "/%d/numeric-records" % w1["id"], {"amount": 0}).status == 400, "amount 0 400")

print("[6] 수정: 제목 PATCH가 current_value를 되돌리지 않음, 목표값 변경 재계산, 측정 방식 불변")
v = A.get(G1 + W + "/%d" % w1["id"]).data
v = (v.get("goal") or v)["version"] if isinstance(v, dict) else 0
A.post(G2 + W + "/%d/numeric-records" % w1["id"], {"amount": 1})
r = A.patch(G1 + W + "/%d" % w1["id"], {"title": "문제 풀기 2", "version": v})
ok(r.status == 200 and dec(sql("SELECT current_value FROM weekly_goals WHERE id=%d" % w1["id"])) == dec(22), "title patch keeps current_value 22", r)
r = A.patch(G2 + W + "/%d" % w1["id"], {"targetValue": 44, "version": r.data["version"]})
ok(r.status == 200 and dec(sql("SELECT progress_rate FROM weekly_goals WHERE id=%d" % w1["id"])) == dec(50), "target 44 -> rate 50.00", r)
ok(A.patch(G1 + W + "/%d" % w1["id"], {"measurementType": "CHECKLIST", "version": r.data["version"]}).code == "GOAL_MEASUREMENT_TYPE_IMMUTABLE", "measurement type immutable")
ok(A.patch(G1 + W + "/%d" % w3["id"], {"targetValue": 5, "version": w3["version"]}).code == "GOAL_NOT_NUMERIC", "target on checklist 422")

print("[7] 주간 PATCH 경합 10라운드: 200 1개 + 409(currentVersion) 1개")
bad = 0
for n in range(10):
    cur = A.get(G1 + W + "/%d" % w2["id"]).data
    ver = (cur.get("goal") or cur)["version"]
    res = parallel([lambda: A.patch(G1 + W + "/%d" % w2["id"], {"title": "A%d" % n, "version": ver}),
                    lambda: A.patch(G2 + W + "/%d" % w2["id"], {"title": "B%d" % n, "version": ver})])
    win = [x for x in res if x.status == 200]
    lose = [x for x in res if x.status == 409]
    # 제목이 매번 바뀌므로 이긴 요청은 version이 ver+1이고, 진 요청은 그 값을 currentVersion으로 받아야 한다.
    if not (len(win) == 1 and len(lose) == 1 and win[0].data["version"] == ver + 1
            and lose[0].code == "VERSION_CONFLICT" and (lose[0].details or {}).get("currentVersion") == ver + 1):
        bad += 1
        print("   round", n, res)
ok(bad == 0, "weekly patch race", bad)

print("[8] 연간 상세 트리·목록·진행 기록 이름")
tree = A.get(G2 + Y + "/%d" % yg["id"]).data
ok(len(tree["monthlyGoals"]) == 1 and len(tree["monthlyGoals"][0]["weeklyGoals"]) == 4, "yearly detail tree 1 monthly / 4 weekly", tree.get("monthlyGoals"))
ids = [x["id"] for x in A.get(G1 + W + "?date=%s" % settings["today"]).data]
ok(ids == sorted([w1["id"], w2["id"], w3["id"], w4["id"]]), "weekly list by today = 4 weeklies in id order", ids)
logs = A.get(G1 + "/api/goals/progress-logs?limit=50").data
types = set(l["targetType"] for l in logs)
ok({"WEEKLY_GOAL", "MONTHLY_GOAL"} <= types and all(l["targetTitle"] for l in logs), "logs for weekly/monthly with titles", types)

print("[9] 조상 삭제로 숨김(DB에서 연간 deleted_at 채움)")
sql("UPDATE yearly_goals SET deleted_at=UTC_TIMESTAMP() WHERE id=%d" % yg["id"])
ok(A.get(G1 + M + "/%d" % mg["id"]).status == 404, "monthly hidden 404")
ok(A.get(G2 + W + "/%d" % w1["id"]).status == 404, "weekly hidden 404")
ok(A.post(G1 + W + "/%d/numeric-records" % w1["id"], {"amount": 1}).status == 404, "record on hidden weekly 404")
ok(A.post(G2 + W, {"monthlyGoalId": mg["id"], "title": "x"}).code == "GOAL_PARENT_UNAVAILABLE", "hidden monthly as parent 422")

finish()
