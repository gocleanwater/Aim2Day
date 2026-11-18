"""단위 8 E2E: 온보딩 상태·임시 저장·한 번에 완료 (실제 MySQL aim2day_review, goal-service 인스턴스 2대)"""
from e2elib import *

A = new_user("o")
B = new_user("p")
isolation_check(A)
G1, G2 = G
ST, DR, CP = "/api/goals/onboarding/status", "/api/goals/onboarding/draft", "/api/goals/onboarding/complete"
today = A.get(G1 + "/api/settings").data["today"]


def drafts(c):
    return sql("SELECT COUNT(*) FROM onboarding_drafts WHERE user_id=%d" % c.user_id)


def weekly(c, wid):
    d = c.get(G1 + "/api/goals/weekly/%d" % wid).data
    return d.get("goal", d)


def goals(c):
    return sql("SELECT COUNT(*) FROM yearly_goals WHERE user_id=%d" % c.user_id)


print("[1] 상태: 목표 0개면 온보딩 필요")
s = A.get(G1 + ST).data
ok(s["needsOnboarding"] is True and s["hasDraft"] is False and s["activeYearlyGoalCount"] == 0 and s["maxYearlyGoalCount"] == 3, "fresh user needs onboarding", s)

print("[2] 두 인스턴스가 같은 사용자의 첫 PUT을 동시에 → 하나만 성공, 나머지 409, 행 1개")
res = parallel([lambda: A.put(G1 + DR, {"currentStep": 1, "draftData": {"from": "g1"}}),
                lambda: A.put(G2 + DR, {"currentStep": 1, "draftData": {"from": "g2"}})])
st = sorted(x.status for x in res)
lose = [x for x in res if x.status == 409]
ok(st in ([200, 409], [201, 409]) and lose[0].code == "VERSION_CONFLICT", "first PUT race one wins", st)
ok(drafts(A) == "1", "exactly one draft row")
ok(A.get(G2 + ST).data["hasDraft"] is True, "status sees draft")

print("[3] JSON 왕복: 중첩·배열·유니코드·숫자 정밀도, MySQL JSON_TYPE = OBJECT")
cur = A.get(G1 + DR).data
payload = {"yearlyGoals": [{"tempId": "y1", "title": "백엔드 🚀 \"취업\"", "tags": ["a", None, True]}],
           "n": {"big": 9007199254740991, "dec": 0.123456789012345, "neg": -12.5e3}, "empty": {}, "list": []}
r = A.put(G2 + DR, {"currentStep": 3, "draftData": payload, "version": cur["version"]})
ok(r.status == 200 and r.data["version"] == cur["version"] + 1, "PUT with version", r)
got = A.get(G1 + DR).data
ok(got["currentStep"] == 3 and got["draftData"] == payload, "draftData round-trips exactly", got)
ok(sql("SELECT JSON_TYPE(draft_data) FROM onboarding_drafts WHERE user_id=%d" % A.user_id) == "OBJECT", "stored as JSON OBJECT in MySQL")
r = A.put(G1 + DR, {"currentStep": 3, "draftData": {}, "version": cur["version"]})
ok(r.status == 409 and (r.details or {}).get("currentVersion") == cur["version"] + 1, "stale version 409 with currentVersion", r)
r = A.put(G1 + DR, {"currentStep": 5, "draftData": {}, "version": got["version"]})
ok(r.status == 400, "currentStep 5 -> 400", r)
ok(B.get(G2 + DR).data is None, "other user has no draft")
stale = A.get(G2 + DR).data["version"]
ok(A.delete(G1 + DR).status == 200 and drafts(A) == "0", "draft deleted")
r = A.put(G2 + DR, {"currentStep": 1, "draftData": {"tab": "B"}})
ok(r.status in (200, 201) and r.data["version"] > stale, "recreated draft version is larger than any old one", r)
r = A.put(G1 + DR, {"currentStep": 4, "draftData": {"tab": "A-stale"}, "version": stale})
ok(r.status == 409 and A.get(G2 + DR).data["draftData"] == {"tab": "B"}, "stale tab cannot overwrite recreated draft", r)

print("[4] 중간 항목이 실패하는 complete → 아무것도 안 만들어지고 임시 저장도 남음")
bw = B.post(G1 + "/api/goals/yearly", {"title": "B연간", "color": "#10B981"}).data
bm = B.post(G1 + "/api/goals/monthly", {"yearlyGoalId": bw["id"], "title": "B월간"}).data
bwk = B.post(G1 + "/api/goals/weekly", {"monthlyGoalId": bm["id"], "title": "B주간"}).data
body_bad = {"yearlyGoals": [{"tempId": "y1", "title": "실패 연간"}],
            "monthlyGoals": [{"tempId": "m1", "yearlyGoalTempId": "y1", "title": "실패 월간"}],
            "weeklyGoals": [{"tempId": "w1", "monthlyGoalTempId": "m1", "title": "실패 주간"}],
            "actions": [{"type": "GOAL", "weeklyGoalTempId": "w1", "title": "정상", "performedDate": today},
                        {"type": "GOAL", "weeklyGoalId": bwk["id"], "title": "남의 주간", "performedDate": today}]}
r = A.post(G2 + CP, body_bad)
ok(r.status in (404, 422), "other user's weekly in last action rejected", r)
ok(goals(A) == "0" and sql("SELECT COUNT(*) FROM actions WHERE user_id=%d" % A.user_id) == "0", "nothing created (rollback)")
ok(drafts(A) == "1", "draft kept after failure")
r = A.post(G1 + CP, {"yearlyGoals": [], "monthlyGoals": [{"tempId": "m1", "yearlyGoalTempId": "nope", "title": "x"}], "weeklyGoals": [], "actions": []})
ok(r.status == 400 and r.code == "VALIDATION_FAILED", "unknown tempId 400", r)

print("[5] 정상 complete → tempId 대응표, 진행률 계산, 임시 저장 삭제")
body = {"yearlyGoals": [{"tempId": "y1", "title": "백엔드 취업"}],
        "monthlyGoals": [{"tempId": "m1", "yearlyGoalTempId": "y1", "title": "알고리즘"}],
        "weeklyGoals": [{"tempId": "w1", "monthlyGoalTempId": "m1", "title": "문제 10개", "measurementType": "NUMERIC", "targetValue": 10}],
        "actions": [{"type": "GOAL", "weeklyGoalTempId": "w1", "title": "2문제", "performedDate": today, "contributionValue": 2},
                    {"type": "MAINTENANCE", "title": "장보기"}]}
r = A.post(G1 + CP, body)
ok(r.status == 201 and set(r.data["yearlyGoals"]) == {"y1"} and len(r.data["actions"]) == 2, "complete 201 with id map", r)
ids = r.data
ok(drafts(A) == "0" and A.get(G2 + ST).data["needsOnboarding"] is False, "draft deleted, onboarding no longer needed")
w = weekly(A, ids["weeklyGoals"]["w1"])
ok(float(w["targetValue"]) == 10 and float(w["currentValue"]) == 0, "weekly created (incomplete action adds nothing)", w)
done = A.put(G1 + "/api/actions/%d/completion" % ids["actions"][0], {"completed": True})
ok(done.status == 200 and float(weekly(A, ids["weeklyGoals"]["w1"])["currentValue"]) == 2, "completing created action updates progress")

print("[6] 두 인스턴스가 동시에 complete(연간 2개씩) 5라운드 → 연간 한도 3을 넘지 않음")
bad = 0
for n in range(5):
    two = lambda tag: {"yearlyGoals": [{"tempId": "a", "title": "%s%d-1" % (tag, n)}, {"tempId": "b", "title": "%s%d-2" % (tag, n)}],
                       "monthlyGoals": [], "weeklyGoals": [], "actions": []}
    res = parallel([lambda: B.post(G1 + CP, two("g1-")), lambda: B.post(G2 + CP, two("g2-"))])
    st = sorted(x.status for x in res)
    active = int(sql("SELECT COUNT(*) FROM yearly_goals WHERE user_id=%d AND deleted_at IS NULL" % B.user_id))
    lim = [x for x in res if x.status == 409]
    if st != [201, 409] or lim[0].code != "GOAL_LIMIT_EXCEEDED" or active != 3:
        bad += 1
        print("   round", n, st, active, res)
    for x in res:
        if x.status == 201:
            for gid in x.data["yearlyGoals"].values():
                B.delete(G1 + "/api/goals/yearly/%d?confirm=true" % gid)
ok(bad == 0, "concurrent complete never exceeds yearly limit", bad)

finish()
