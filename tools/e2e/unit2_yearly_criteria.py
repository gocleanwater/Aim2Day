"""단위 2 E2E: 연간 목표·달성 기준·진행 기록 (실제 MySQL aim2day_review, goal-service 인스턴스 2대)"""
from e2elib import *

A = new_user("y")
B = new_user("z")
isolation_check(A)
G1, G2 = G
Y = "/api/goals/yearly"

print("[1] 생성·한도·인스턴스 식별")
r = A.post(G1 + Y, {"title": "백엔드 취업"})
ok(r.status == 201 and r.data["color"] == "#3B82F6" and r.data["criteria"]["state"] == "NOT_CONFIGURED", "create defaults", r)
ok(r.headers.get("X-Instance-Id") == "aim2day-goal-service-review-1", "X-Instance-Id", r.headers.get("X-Instance-Id"))
ok(r.data["createdAt"].endswith("Z"), "createdAt UTC", r.data.get("createdAt"))
g1 = r.data["id"]
r = A.post(G2 + Y, {"title": "영어"})
g2 = r.data["id"]
res = parallel([lambda u=u: A.post(u + Y, {"title": "동시"}) for u in (G1, G2, G1, G2)])
codes = sorted(x.status for x in res)
ok(codes == [201, 409, 409, 409], "4 concurrent creates at 2 active -> exactly one 201", codes)
ok(all(x.code == "GOAL_LIMIT_EXCEEDED" for x in res if x.status == 409), "409 code GOAL_LIMIT_EXCEEDED")
lst = A.get(G2 + Y).data
ok(lst["activeCount"] == 3 and len(lst["items"]) == 3, "active count 3", lst["activeCount"])
g3 = [i["id"] for i in lst["items"] if i["id"] not in (g1, g2)][0]

print("[2] 소유권")
ok(B.get(G1 + Y + "/%d" % g1).status == 404, "other user's goal 404")
ok(B.patch(G2 + Y + "/%d" % g1, {"title": "x", "version": 0}).status == 404, "other user's patch 404")
ok(B.get(G1 + Y).data["activeCount"] == 0, "other user's list empty")

print("[3] PATCH 경합 10라운드: 서로 다른 값, 같은 version → 200 1개 + 409(currentVersion) 1개")
bad = 0
for n in range(10):
    v = A.get(G1 + Y + "/%d" % g1).data["goal"]["version"]
    res = parallel([lambda: A.patch(G1 + Y + "/%d" % g1, {"title": "A%d" % n, "version": v}),
                    lambda: A.patch(G2 + Y + "/%d" % g1, {"title": "B%d" % n, "version": v})])
    s = sorted(x.status for x in res)
    c409 = [x for x in res if x.status == 409]
    if s != [200, 409] or not all(isinstance((x.details or {}).get("currentVersion"), int) for x in c409):
        bad += 1
        print("   round", n, res)
ok(bad == 0, "patch race rounds", bad)
r = A.patch(G1 + Y + "/%d" % g1, {"description": "설명", "version": A.get(G1 + Y + "/%d" % g1).data["goal"]["version"]})
v = r.data["version"]
r = A.patch(G2 + Y + "/%d" % g1, {"description": None, "version": v})
ok(r.status == 200 and r.data["description"] is None and r.data["title"].startswith(("A", "B")), "null clears description, title kept", r)

print("[4] 기준 초안 → 확정(기준선) → 달성 → 개정")
C = Y + "/%d/criteria" % g1
gv = A.get(G1 + C).data["goalVersion"]
r = A.put(G1 + C, {"goalVersion": gv, "items": [{"text": "포트폴리오", "weight": 40}, {"text": "자격증", "weight": 10}, {"text": "코테", "weight": 50}]})
ok(r.status == 200 and r.data["totalWeight"] == 100 and r.data["goalVersion"] == gv + 1, "draft save bumps goalVersion", r)
items = {i["text"]: i["id"] for i in r.data["items"]}
r = A.post(G2 + C + "/confirm", {"goalVersion": gv + 1, "baselineAchievedCriterionIds": [items["자격증"]]})
ok(r.status == 200 and r.data["state"] == "CONFIRMED" and r.data["initialBaselineRate"] == 10 and r.data["achievementRate"] == 10, "confirm with baseline 10", r)
ok(A.post(G1 + C + "/confirm", {"goalVersion": r.data["goalVersion"], "baselineAchievedCriterionIds": []}).code == "CRITERIA_ALREADY_CONFIRMED", "confirm twice 409")
r = A.put(G1 + C + "/%d/achievement" % items["포트폴리오"], {"achieved": True, "memo": "배포 완료"})
ok(r.status == 200 and r.data["achievementRate"] == 50, "achieve 40 -> 50", r)
n_logs = len(A.get(G2 + "/api/goals/progress-logs?limit=50").data)
r = A.put(G2 + C + "/%d/achievement" % items["포트폴리오"], {"achieved": True})
ok(r.status == 200 and len(A.get(G1 + "/api/goals/progress-logs?limit=50").data) == n_logs, "same achievement no-op, no log")
gv = A.get(G1 + C).data["goalVersion"]
r = A.put(G1 + C, {"goalVersion": gv, "items": [{"id": items["포트폴리오"], "text": "포트폴리오", "weight": 40}, {"id": items["자격증"], "text": "자격증", "weight": 10}, {"text": "오픈소스", "weight": 50}], "memo": "코테를 오픈소스로"})
ok(r.status == 200 and r.data["achievementRate"] == 50, "revision keeps achievements (rate 50)", r)
logs = A.get(G2 + "/api/goals/progress-logs?limit=50").data
ok(logs[0]["changeType"] == "CRITERIA_REVISION" and float(logs[0]["previousRate"]) == float(logs[0]["newRate"]) == 50.0, "revision logged even if rate same", logs[0])
ok(logs[0]["targetTitle"] and logs[0]["targetType"] == "YEARLY_GOAL", "log has target title")
ok(B.get(G1 + "/api/goals/progress-logs").data == [], "other user's logs hidden")

print("[5] 달성·개정·순서 저장을 두 인스턴스에서 동시에 20라운드 → 500 없음, 달성률 = 달성한 배점 합")
bad = 0
errs = 0
for n in range(20):
    cr = A.get(G1 + C).data
    ids = [i["id"] for i in cr["items"]]
    target = ids[n % len(ids)]
    want = n % 2 == 0
    order = [g1, g2, g3] if n % 2 else [g3, g2, g1]
    res = parallel([
        lambda: A.put(G1 + C + "/%d/achievement" % target, {"achieved": want}),
        lambda: A.put(G2 + C, {"goalVersion": cr["goalVersion"], "items": [{"id": i["id"], "text": i["text"], "weight": i["weight"]} for i in cr["items"]], "memo": "r%d" % n}),
        lambda: A.put(G2 + Y + "/order", {"year": 2026 if False else lst["year"], "goalIds": order}),
    ])
    # 세 요청 모두 성공해야 하고, 각자의 효과가 실제로 반영돼야 한다(거절돼도 일관성 검사는 통과하므로).
    if any(x.status != 200 for x in res):
        errs += 1
        print("   round", n, res)
    after = A.get(G2 + C).data
    s = sum(i["weight"] for i in after["items"] if i["isAchieved"])
    applied = (next(i["isAchieved"] for i in after["items"] if i["id"] == target) == want
               and after["goalVersion"] == cr["goalVersion"] + 1
               and [i["id"] for i in A.get(G1 + Y).data["items"]] == order)
    if s != after["achievementRate"] or not applied:
        bad += 1
        print("   mismatch round", n, s, after["achievementRate"], "applied", applied)
ok(errs == 0, "all concurrent requests 200 (no deadlock, no rejection)", errs)
ok(bad == 0, "effects applied and achievementRate consistent", bad)
chain = sorted(A.get(G1 + "/api/goals/progress-logs?limit=50").data, key=lambda l: l["id"])
breaks = sum(1 for a, b in zip(chain, chain[1:]) if float(a["newRate"]) != float(b["previousRate"]))
ok(breaks == 0, "progress log chain previousRate == prior newRate", breaks)

print("[6] 순서 저장")
r = A.put(G1 + Y + "/order", {"year": lst["year"], "goalIds": [g2, g3, g1]})
ok(r.status == 200 and [i["id"] for i in A.get(G2 + Y).data["items"]] == [g2, g3, g1], "reorder applied", r)
ok(A.put(G1 + Y + "/order", {"year": lst["year"], "goalIds": [g1, g2]}).code == "GOAL_ORDER_MISMATCH", "missing id 422")

print("[7] 진행 기록 limit 검증, 미인증")
ok(A.get(G1 + "/api/goals/progress-logs?limit=51").status == 400, "limit 51 400")
ok(Client().get(G1 + Y).status == 401, "unauthenticated 401")
ok(A.req("POST", G1 + Y, {"title": "x"}, csrf=False).status == 403, "no CSRF 403")

finish()
