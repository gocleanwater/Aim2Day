"""단위 7 E2E: 달력 항목·기간 조회·대시보드 (실제 MySQL aim2day_review, goal-service 인스턴스 2대)"""
import time
from datetime import date, timedelta
from e2elib import *

A = new_user("c")
B = new_user("d")
isolation_check(A)
G1, G2 = G
CAL = "/api/calendar/items"
today = date.fromisoformat(A.get(G1 + "/api/settings").data["today"])


def d(n):
    return (today + timedelta(days=n)).isoformat()


print("[1] 생성·조회·소유권(두 인스턴스 교차)")
yg = A.post(G1 + "/api/goals/yearly", {"title": "백엔드", "color": "#3B82F6"}).data
r = A.post(G2 + CAL, {"itemType": "TASK", "title": "과제 제출", "startDate": d(2), "yearlyGoalId": yg["id"]})
ok(r.status == 201 and r.data["endDate"] == d(2) and r.data["yearlyGoal"] == {"id": yg["id"], "title": "백엔드", "color": "#3B82F6"} and r.data["daysUntilDue"] == 2, "TASK created with goal", r)
task = r.data
r = A.post(G1 + CAL, {"itemType": "EVENT", "title": "발표", "startDate": d(-1), "endDate": d(1)})
ok(r.status == 201 and r.data["yearlyGoal"] is None, "multi-day EVENT unlinked", r)
ev = r.data
ok(A.post(G2 + CAL, {"itemType": "TASK", "title": "x", "startDate": d(1), "endDate": d(2)}).status == 400, "TASK endDate != startDate 400")
ok(A.post(G1 + CAL, {"itemType": "EVENT", "title": "x", "startDate": d(2), "endDate": d(1)}).status == 400, "EVENT end < start 400")
r = B.post(G2 + CAL, {"itemType": "TASK", "title": "x", "startDate": d(1), "yearlyGoalId": yg["id"]})
ok(r.status == 422 and r.code == "GOAL_PARENT_UNAVAILABLE", "other user's goal 422", r)
ok(B.get(G1 + CAL + "?from=%s&to=%s" % (d(-5), d(5))).data == [], "other user sees nothing")
ok(B.delete(G2 + CAL + "/%d" % task["id"]).status == 404, "other user's delete 404")

print("[2] 기간 조회: 걸친 일정 포함, 100일 200 / 101일 422 / to<from 422, 목표 필터")
items = A.get(G2 + CAL + "?from=%s&to=%s" % (d(0), d(5))).data
ok([x["id"] for x in items] == [ev["id"], task["id"]], "overlapping EVENT included, ordered by startDate", [x["id"] for x in items])
ok(A.get(G1 + CAL + "?from=%s&to=%s" % (d(0), d(99))).status == 200, "100 days 200")
r = A.get(G1 + CAL + "?from=%s&to=%s" % (d(0), d(100)))
ok(r.status == 422 and r.code == "REPORT_INVALID_PERIOD", "101 days 422", r)
ok(A.get(G2 + CAL + "?from=%s&to=%s" % (d(1), d(0))).code == "REPORT_INVALID_PERIOD", "to < from 422")
ids = lambda q: [x["id"] for x in A.get(G1 + CAL + "?from=%s&to=%s%s" % (d(-5), d(5), q)).data]
ok(ids("&yearlyGoalIds=%d&includeUnlinked=false" % yg["id"]) == [task["id"]], "filter linked only")
ok(ids("&yearlyGoalIds=%d&includeUnlinked=true" % yg["id"]) == [ev["id"], task["id"]], "filter linked + unlinked")

print("[3] 같은 항목을 두 인스턴스가 동시에 PATCH 10라운드 → 200 1개 + 409(currentVersion)")
bad = 0
for n in range(10):
    ver = next(x for x in A.get(G1 + CAL + "?from=%s&to=%s" % (d(0), d(5))).data if x["id"] == task["id"])["version"]
    res = parallel([lambda: A.patch(G1 + CAL + "/%d" % task["id"], {"title": "A%d" % n, "version": ver}),
                    lambda: A.patch(G2 + CAL + "/%d" % task["id"], {"title": "B%d" % n, "version": ver})])
    win = [x for x in res if x.status == 200]
    lose = [x for x in res if x.status == 409]
    if not (len(win) == 1 and len(lose) == 1 and win[0].data["version"] == ver + 1
            and lose[0].code == "VERSION_CONFLICT" and (lose[0].details or {}).get("currentVersion") == ver + 1):
        bad += 1
        print("   round", n, res)
ok(bad == 0, "calendar patch race", bad)
r = A.patch(G2 + CAL + "/%d" % task["id"], {"itemType": "EVENT", "version": ver + 1})
ok(r.status == 422 and r.code == "CALENDAR_ITEM_TYPE_IMMUTABLE", "itemType immutable 422", r)

print("[4] 완료: 동시 완료도 한 번만, 같은 상태 반복은 completedAt 유지, 대시보드 tasks에서 빠짐")
res = parallel([lambda: A.put(G1 + CAL + "/%d/completion" % task["id"], {"completed": True}),
                lambda: A.put(G2 + CAL + "/%d/completion" % task["id"], {"completed": True})])
ok(all(x.status == 200 for x in res) and res[0].data["completedAt"] == res[1].data["completedAt"], "concurrent completion same completedAt", [x.status for x in res])
first = res[0].data
time.sleep(1.2)  # 다른 초에 다시 보내도 완료 시각·version이 바뀌지 않아야 한다(같은 초 덮어쓰기와 구분)
r = A.put(G2 + CAL + "/%d/completion" % task["id"], {"completed": True})
ok(r.status == 200 and r.data["completedAt"] == first["completedAt"] and r.data["version"] == first["version"], "repeat completion 1s later keeps completedAt and version", r)
dash = A.get(G1 + "/api/calendar/dashboard").data
ok(task["id"] not in [x["id"] for x in dash["tasks"]], "completed TASK not in dashboard tasks")

print("[5] 대시보드 경계: 오늘+7 포함·+8 제외, 어제 끝난 일정 D+1, 8일 전 제외")
e7 = A.post(G1 + CAL, {"itemType": "EVENT", "title": "+7", "startDate": d(7)}).data
e8 = A.post(G2 + CAL, {"itemType": "EVENT", "title": "+8", "startDate": d(8)}).data
p1 = A.post(G1 + CAL, {"itemType": "EVENT", "title": "어제", "startDate": d(-1)}).data
p8 = A.post(G2 + CAL, {"itemType": "EVENT", "title": "8일 전", "startDate": d(-8)}).data
t2 = A.post(G1 + CAL, {"itemType": "TASK", "title": "지난 마감", "startDate": d(-3)}).data
dash = A.get(G2 + "/api/calendar/dashboard").data
ev_ids = [x["id"] for x in dash["events"]]
ok(dash["today"] == today.isoformat() and ev["id"] in ev_ids and e7["id"] in ev_ids and e8["id"] not in ev_ids, "events window", ev_ids)
past = {x["id"]: x["daysSinceEnd"] for x in dash["pastEvents"]}
ok(past.get(p1["id"]) == 1 and p8["id"] not in past, "pastEvents D+1, 8 days ago excluded", past)
ok(t2["id"] in [x["id"] for x in dash["tasks"]], "overdue incomplete TASK listed")

print("[6] 연결 목표 삭제 → yearlyGoal null·미연결로 필터, 복원 → 다시 연결")
ok(A.delete(G1 + "/api/goals/yearly/%d?confirm=true" % yg["id"]).status == 200, "yearly deleted")
x = next(i for i in A.get(G2 + CAL + "?from=%s&to=%s" % (d(-5), d(5))).data if i["id"] == task["id"])
ok(x["yearlyGoal"] is None, "deleted goal shows as null")
ok(ids("&yearlyGoalIds=%d&includeUnlinked=false" % yg["id"]) == [], "deleted goal not matched by filter")
ok(task["id"] in ids("&includeUnlinked=true&yearlyGoalIds="), "treated as unlinked")
A.post(G2 + "/api/goals/yearly/%d/restore" % yg["id"])
x = next(i for i in A.get(G1 + CAL + "?from=%s&to=%s" % (d(-5), d(5))).data if i["id"] == task["id"])
ok((x["yearlyGoal"] or {}).get("id") == yg["id"], "restored goal linked again")

print("[7] 즉시 영구 삭제")
ok(A.delete(G1 + CAL + "/%d" % ev["id"]).status == 200 and sql("SELECT COUNT(*) FROM calendar_items WHERE id=%d" % ev["id"]) == "0", "hard delete")

finish()
