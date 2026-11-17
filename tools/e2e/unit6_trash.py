"""단위 6 E2E: 목표 삭제·휴지통·복원과 30일 자동 정리 (실제 MySQL aim2day_review, goal-service 인스턴스 2대)
정리 주기를 짧게 띄워야 한다: AIM2DAY_TRASH_PURGE_CRON="*/5 * * * * *" bash review_up.sh
"""
import subprocess
import time
from e2elib import *

A = new_user("t")
B = new_user("u")
isolation_check(A)
G1, G2 = G
Y, M, W, ACT = "/api/goals/yearly", "/api/goals/monthly", "/api/goals/weekly", "/api/actions"
today = A.get(G1 + "/api/settings").data["today"]


def tree(title, kind="CHECKLIST"):
    y = A.post(G1 + Y, {"title": title, "color": "#10B981"}).data
    m = A.post(G2 + M, {"yearlyGoalId": y["id"], "title": title + "-월"}).data
    w = A.post(G1 + W, {"monthlyGoalId": m["id"], "title": title + "-주", "measurementType": kind} | ({"targetValue": 10} if kind == "NUMERIC" else {})).data
    return y, m, w


def action(w, title):
    r = A.post(G2 + ACT, {"type": "GOAL", "weeklyGoalId": w["id"], "title": title, "performedDate": today})
    assert r.status == 201, r
    return r.data


def exists(table, gid):
    return sql("SELECT COUNT(*) FROM %s WHERE id=%d" % (table, gid)) == "1"


print("[1] 삭제 확인(409)과 즉시 삭제·복원")
y0, m0, w0 = tree("기본")
w0b = A.post(G2 + W, {"monthlyGoalId": m0["id"], "title": "빈 주간"}).data
a0 = action(w0, "미완료")
r = A.delete(G1 + Y + "/%d" % y0["id"])
d = r.details or {}
ok(r.status == 409 and r.code == "GOAL_DELETE_CONFIRM_REQUIRED" and d.get("monthlyGoalCount") == 1
   and d.get("weeklyGoalCount") == 2 and d.get("incompleteActionCount") == 1 and "restoreDeadline" in d and "notice" in d,
   "yearly delete without confirm -> 409 with counts", r)
ok(exists("yearly_goals", y0["id"]) and sql("SELECT deleted_at IS NULL FROM yearly_goals WHERE id=%d" % y0["id"]) == "1", "nothing deleted on 409")
r = A.delete(G2 + W + "/%d" % w0b["id"])
ok(r.status == 200 and r.data["deletedAt"] and r.data["restoreDeadline"], "empty weekly deleted without confirm", r)
ok(B.delete(G1 + Y + "/%d" % y0["id"] + "?confirm=true").status == 404, "other user's goal 404")
trash = A.get(G1 + "/api/goals/trash").data
ok([(t["level"], t["id"]) for t in trash] == [("WEEKLY", w0b["id"])] and trash[0]["remainingDays"] == 30, "trash lists the weekly", trash)
r = A.post(G2 + W + "/%d/restore" % w0b["id"])
ok(r.status == 200 and A.get(G1 + "/api/goals/trash").data == [], "weekly restored, trash empty", r)

print("[2] 연간 삭제(확인) → 하위·행동 숨김 → 휴지통 → 복원으로 함께 복귀")
r = A.delete(G1 + Y + "/%d?confirm=true" % y0["id"])
ok(r.status == 200, "yearly deleted with confirm", r)
ok(A.get(G2 + W + "/%d" % w0["id"]).status == 404, "child weekly hidden")
ok(a0["id"] not in [x["id"] for x in A.get(G1 + ACT + "?date=%s" % today).data["items"]], "incomplete action hidden")
ok(A.post(G2 + M + "/%d/restore" % m0["id"]).status == 404, "child not directly deleted -> restore 404")
r = A.post(G1 + Y + "/%d/restore" % y0["id"])
ok(r.status == 200 and A.get(G2 + W + "/%d" % w0["id"]).status == 200, "yearly restore brings children back", r)
ok(a0["id"] in [x["id"] for x in A.get(G2 + ACT + "?date=%s" % today).data["items"]], "action visible again")

print("[3] 삭제와 그 아래 행동 완료가 두 인스턴스에서 동시에 10라운드 → users 잠금으로 직렬화")
bad = 0
outcomes = set()
for n in range(10):
    a = action(w0, "경합 %d" % n)
    res = parallel([lambda: A.delete(G1 + Y + "/%d?confirm=true" % y0["id"]),
                    lambda: A.put(G2 + ACT + "/%d/completion" % a["id"], {"completed": True})])
    dr, cr = res
    done = sql("SELECT is_completed FROM actions WHERE id=%d" % a["id"]) == "1"
    outcome = (dr.status, cr.status)
    outcomes.add(outcome)
    if outcome not in ((200, 200), (200, 404)) or done != (cr.status == 200):
        bad += 1
        print("   round", n, outcome, done)
    A.post(G1 + Y + "/%d/restore" % y0["id"])
ok(bad == 0, "delete vs completion race consistent %s" % sorted(outcomes), bad)

print("[4] 30일 지난 삭제를 두 인스턴스가 정리 → 완료 기록 있는 경로는 보존, 빈 경로는 영구 삭제")
y1, m1, w1 = tree("보존")
keep = action(w1, "완료함")
A.put(G1 + ACT + "/%d/completion" % keep["id"], {"completed": True})
drop1 = action(w1, "미완료1")
y2, m2, w2 = tree("삭제", "NUMERIC")
drop2 = action(w2, "미완료2")
w2_logs = sql("SELECT COUNT(*) FROM goal_progress_logs WHERE target_type='WEEKLY_GOAL' AND target_id=%d" % w2["id"])
ok(A.delete(G1 + Y + "/%d?confirm=true" % y1["id"]).status == 200 and A.delete(G2 + Y + "/%d?confirm=true" % y2["id"]).status == 200, "both yearlies deleted")
sql("UPDATE yearly_goals SET deleted_at = UTC_TIMESTAMP() - INTERVAL 31 DAY WHERE id IN (%d, %d)" % (y1["id"], y2["id"]))
r = A.post(G1 + Y + "/%d/restore" % y1["id"])
ok(r.status == 409 and r.code == "GOAL_RESTORE_EXPIRED", "expired restore 409 (before purge)", r)
for _ in range(30):
    if not exists("yearly_goals", y2["id"]):
        break
    time.sleep(1)
ok(not any(exists(t, i) for t, i in (("yearly_goals", y2["id"]), ("monthly_goals", m2["id"]), ("weekly_goals", w2["id"]), ("actions", drop2["id"]))), "empty path hard-deleted")
ok(all(exists(t, i) for t, i in (("yearly_goals", y1["id"]), ("monthly_goals", m1["id"]), ("weekly_goals", w1["id"]), ("actions", keep["id"]))), "path with completed action preserved")
ok(not exists("actions", drop1["id"]), "incomplete action under preserved path deleted")
ok(sql("SELECT deleted_at IS NOT NULL FROM yearly_goals WHERE id=%d" % y1["id"]) == "1", "preserved yearly stays deleted")
ok(sql("SELECT COUNT(*) FROM goal_progress_logs WHERE target_type='WEEKLY_GOAL' AND target_id=%d" % w2["id"]) == w2_logs, "progress logs kept for purged goal")
ok(A.get(G1 + "/api/goals/trash").data == [], "expired goals not in trash")
time.sleep(6)  # 한 회차 더 돌게 둔다(보존 root 재처리는 멱등)
logs = [subprocess.run(["docker", "logs", "aim2day-goal-service-review-%d" % i], capture_output=True, text=True).stdout for i in (1, 2)]
ok(all("휴지통 정리: root" in l for l in logs), "both instances ran the purge job")
ok(not any(("휴지통 정리 실패" in l) or (" ERROR " in l) for l in logs), "no purge failure or ERROR in logs")
ok(all(exists(t, i) for t, i in (("yearly_goals", y1["id"]), ("weekly_goals", w1["id"]), ("actions", keep["id"]))), "preserved rows unchanged after another round")

finish()
