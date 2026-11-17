"""단위 5 E2E: 사분면 분류(좌표 놓기·확정·취소·정렬)와 두 인스턴스 동시 요청 (실제 MySQL aim2day_review)"""
from datetime import date, timedelta
from e2elib import *

A = new_user("q")
B = new_user("r")
isolation_check(A)
G1, G2 = G
ACT = "/api/actions"

settings = A.get(G1 + "/api/settings").data
today = settings["today"]
yesterday = (date.fromisoformat(today) - timedelta(days=1)).isoformat()


def P(x, y):
    return {"importance": x, "urgency": y}


def put(base, aid, x, y, client=None):
    return (client or A).put(base + ACT + "/%d/placement" % aid, P(x, y))


def confirm(base):
    return A.post(base + ACT + "/placements/confirm", {})


def listing(base, sort=None):
    q = "?date=%s" % today + ("&sort=%s" % sort if sort else "")
    return A.get(base + ACT + q).data


def pl(aid, base=G1):
    return next(x["placement"] for x in listing(base)["items"] if x["id"] == aid)


def rows(aid):
    return sql("SELECT COUNT(*) FROM action_quadrant_placements WHERE action_id=%d" % aid)


print("[1] 준비: GOAL 행동 2개, MAINTENANCE 2개(오늘·어제)")
yg = A.post(G1 + "/api/goals/yearly", {"title": "백엔드", "color": "#10B981"}).data
mg = A.post(G2 + "/api/goals/monthly", {"yearlyGoalId": yg["id"], "title": "알고리즘"}).data
wk = A.post(G1 + "/api/goals/weekly", {"monthlyGoalId": mg["id"], "title": "체크형"}).data
g1 = A.post(G1 + ACT, {"type": "GOAL", "weeklyGoalId": wk["id"], "title": "보고서", "performedDate": today}).data
g2 = A.post(G2 + ACT, {"type": "GOAL", "weeklyGoalId": wk["id"], "title": "문제", "performedDate": today}).data
m1 = A.post(G1 + ACT, {"type": "MAINTENANCE", "title": "장보기", "performedDate": today}).data
my = A.post(G2 + ACT, {"type": "MAINTENANCE", "title": "어제 일", "performedDate": yesterday}).data
ok(all(x and "id" in x for x in (g1, g2, m1, my)), "actions created")
ok(pl(g1["id"])["state"] == "UNCLASSIFIED", "new action UNCLASSIFIED")

print("[2] 검증·오류")
r = put(G1, g1["id"], 49, 10)
ok(r.status == 422 and r.code == "QUADRANT_OUT_OF_RANGE" and r.details == {"minImportance": 50}, "GOAL x=49 -> 422", r)
ok(put(G2, m1["id"], 0, 0).status == 200, "MAINTENANCE x=0 -> 200")
ok(A.put(G1 + ACT + "/%d/placement" % g1["id"], {"importance": 70, "urgency": None}).status == 400, "one null -> 400")
ok(A.put(G2 + ACT + "/%d/placement" % g1["id"], {"importance": 70}).status == 400, "missing field -> 400")
ok(put(G1, g1["id"], 101, 0).status == 400, "out of 0..100 -> 400")
r = put(G2, my["id"], 10, 10)
ok(r.status == 422 and r.code == "QUADRANT_DATE_INVALID" and r.details == {"today": today, "performedDate": yesterday}, "yesterday action -> 422", r)
ok(put(G1, g1["id"], 80, 90, client=B).status == 404, "other user's action -> 404")

print("[3] 같은 행동을 두 인스턴스가 동시에 놓기 10라운드 → 모두 200, 행 1개, 응답과 DB 일치")
bad = 0
for n in range(10):
    vals = [(50 + n, n), (60 + n, 10 + n)]
    res = parallel([lambda: put(G1, g2["id"], *vals[0]), lambda: put(G2, g2["id"], *vals[1])])
    got = sql("SELECT pending_importance_x, pending_urgency_y FROM action_quadrant_placements WHERE action_id=%d" % g2["id"]).split()
    bodies = [(x.data or {}).get("placement", {}).get("pending") for x in res]
    if any(x.status != 200 for x in res) or rows(g2["id"]) != "1" or tuple(map(int, got)) not in vals             or bodies != [P(*vals[0]), P(*vals[1])]:
        bad += 1
        print("   round", n, [x.status for x in res], rows(g2["id"]), got)
ok(bad == 0, "concurrent same-action placement", bad)
ok(pl(g2["id"])["state"] == "PENDING", "g2 PENDING")

print("[4] 상태 흐름: 놓기 → 확정 → 옮기기 → 변경 취소 → 트레이 → 확정")
put(G1, g1["id"], 80, 90)
ok(pl(g1["id"]) == {"state": "PENDING", "confirmed": None, "pending": P(80, 90), "displayed": P(80, 90)}, "PENDING", pl(g1["id"]))
r = confirm(G2)
ok(r.status == 200 and r.data["date"] == today and r.data["confirmedCount"] == 3 and r.data["unclassifiedCount"] == 0, "confirm 3 (g1, g2, m1)", r)
ok(listing(G1)["classification"]["confirmedToday"] is True, "confirmedToday true")
ok(pl(g1["id"]) == {"state": "CONFIRMED", "confirmed": P(80, 90), "pending": None, "displayed": P(80, 90)}, "CONFIRMED", pl(g1["id"]))
put(G1, g1["id"], 60, 30)
ok(pl(g1["id"], G2) == {"state": "RECLASSIFYING", "confirmed": P(80, 90), "pending": P(60, 30), "displayed": P(60, 30)}, "RECLASSIFYING", pl(g1["id"], G2))
r = A.delete(G2 + ACT + "/%d/placement/pending" % g1["id"])
ok(r.status == 200 and r.data["placement"]["state"] == "CONFIRMED" and r.data["placement"]["displayed"] == P(80, 90), "cancel pending -> back to (80,90)", r)
r = put(G1, g1["id"], None, None)
ok(r.status == 200 and r.data["placement"] == {"state": "PENDING_UNCLASSIFY", "confirmed": P(80, 90), "pending": None, "displayed": None}, "tray -> PENDING_UNCLASSIFY", r)
c = listing(G2)["classification"]
ok(c["unconfirmedChanges"] == 1, "unconfirmedChanges 1", c)
r = confirm(G1)
ok(r.status == 200 and r.data["confirmedCount"] == 0 and r.data["unclassifiedCount"] == 1, "confirm unclassifies 1", r)
ok(pl(g1["id"])["state"] == "UNCLASSIFIED" and rows(g1["id"]) == "0", "g1 UNCLASSIFIED, row deleted")

print("[5] 확정과 놓기 동시 10라운드 → 둘 다 200, 결과는 두 순서 중 하나와 정확히 일치")
bad = 0
for n in range(10):
    old, new = (70, n), (90, 50 + n)
    put(G1, g2["id"], *old)
    confirm(G1)
    put(G2, g2["id"], 75, 5 + n)  # 확정된 old 위에 미확정 하나
    res = parallel([lambda: confirm(G1), lambda: put(G2, g2["id"], *new)])
    p = pl(g2["id"])
    first_put = {"state": "CONFIRMED", "confirmed": P(*new), "pending": None, "displayed": P(*new)}
    first_confirm = {"state": "RECLASSIFYING", "confirmed": P(75, 5 + n), "pending": P(*new), "displayed": P(*new)}
    cr, pr = res
    # 응답 본문도 같은 순서를 가리켜야 한다. 확정은 어느 순서든 1건이다. PUT 응답은 둘 다 RECLASSIFYING이지만
    # 확정 좌표가 다르다: PUT이 먼저면 그 전 확정값 old, 확정이 먼저면 방금 확정된 (75, 5+n).
    want_conf = P(*old) if p == first_put else P(75, 5 + n)
    body_ok = cr.status == 200 and cr.data["confirmedCount"] == 1 and pr.status == 200         and pr.data["placement"] == {"state": "RECLASSIFYING", "confirmed": want_conf, "pending": P(*new), "displayed": P(*new)}
    if any(x.status != 200 for x in res) or p not in (first_put, first_confirm) or not body_ok:
        bad += 1
        print("   round", n, [x.status for x in res], p)
ok(bad == 0, "confirm vs placement race", bad)

print("[6] 정렬: x+y → x → id, 미분류는 뒤 / GROUP은 GOAL 먼저")
put(G1, g1["id"], 50, 40)        # 90
put(G2, g2["id"], 60, 30)        # 90, x 더 큼 → g1보다 앞
put(G1, m1["id"], 10, 95)        # 105 → 맨 앞
m2 = A.post(G1 + ACT, {"type": "MAINTENANCE", "title": "미분류", "performedDate": today}).data
order = [x["id"] for x in listing(G2, "IMPORTANCE_URGENCY")["items"]]
ok(order == [m1["id"], g2["id"], g1["id"], m2["id"]], "IMPORTANCE_URGENCY order", order)
order = [x["id"] for x in listing(G1, "IMPORTANCE")["items"]]
ok(order == [g2["id"], g1["id"], m1["id"], m2["id"]], "IMPORTANCE order", order)
order = [x["id"] for x in listing(G2, "URGENCY")["items"]]
ok(order == [m1["id"], g1["id"], g2["id"], m2["id"]], "URGENCY order", order)
order = [x["id"] for x in listing(G1, "GROUP")["items"]]
ok(order == sorted([g1["id"], g2["id"]]) + sorted([m1["id"], m2["id"]]), "GROUP order", order)
c = listing(G2)["classification"]
ok(c == {"unclassified": 1, "unconfirmedChanges": 3, "confirmed": 0, "confirmedToday": True}, "classification counts (disjoint)", c)

print("[7] 어제 목록은 확정 좌표만, 오늘 확정은 어제 행을 건드리지 않음")
yl = A.get(G1 + ACT + "?date=%s" % yesterday).data
ok(yl["classification"]["unconfirmedChanges"] == 0 and all(x["placement"]["pending"] is None for x in yl["items"]), "yesterday view ignores pending", yl["classification"])
ok(rows(my["id"]) == "0", "yesterday action untouched")

finish()
