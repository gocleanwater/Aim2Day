"""서버 E2E 공용 도구. review 컨테이너에 직접 IP로 요청한다(프록시는 운영 컨테이너만 가리킨다)."""
import json, subprocess, time, re, urllib.request, urllib.error, http.cookiejar, threading

DB_ARGS = ["docker", "exec", "aim2day-mysql", "mysql", "-uaim2day_app", "-paim2daypass!", "aim2day_review", "-N", "-e"]


def ip(name):
    return subprocess.check_output(["docker", "inspect", "-f", "{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}", name], text=True).strip()


def sql(q):
    out = subprocess.run(DB_ARGS + [q], capture_output=True, text=True)
    return out.stdout.strip()


USER = "http://%s:8080" % ip("aim2day-user-service-review")
G = ["http://%s:8080" % ip("aim2day-goal-service-review-1"), "http://%s:8080" % ip("aim2day-goal-service-review-2")]

PASS = 0
FAIL = 0


def ok(cond, label, got=None):
    global PASS, FAIL
    if cond:
        PASS += 1
        print("  PASS", label)
    else:
        FAIL += 1
        print("  FAIL", label, "" if got is None else "-> %s" % (got,))


class Client:
    """세션·CSRF 쿠키를 들고 다니는 브라우저 흉내. 모든 인스턴스에 같은 쿠키를 보낸다."""

    def __init__(self, session=None, xsrf=None):
        self.session = session
        self.xsrf = xsrf

    def req(self, method, url, body=None, csrf=True):
        data = None if body is None else json.dumps(body).encode()
        r = urllib.request.Request(url, data=data, method=method)
        cookies = []
        if self.session:
            cookies.append("SESSION=" + self.session)
        if self.xsrf:
            cookies.append("XSRF-TOKEN=" + self.xsrf)
            if csrf and method != "GET":
                r.add_header("X-XSRF-TOKEN", self.xsrf)
        if cookies:
            r.add_header("Cookie", "; ".join(cookies))
        if data is not None:
            r.add_header("Content-Type", "application/json")
        try:
            resp = urllib.request.urlopen(r, timeout=30)
            status, raw, headers = resp.status, resp.read(), resp.headers
        except urllib.error.HTTPError as e:
            status, raw, headers = e.code, e.read(), e.headers
        for c in headers.get_all("Set-Cookie") or []:
            m = re.match(r"(SESSION|XSRF-TOKEN)=([^;]*)", c)
            if m:
                if m.group(1) == "SESSION":
                    self.session = m.group(2) or None
                else:
                    self.xsrf = m.group(2) or None
        try:
            js = json.loads(raw) if raw else None
        except ValueError:
            js = raw.decode(errors="replace")
        return Resp(status, js, headers)

    def get(self, url):
        return self.req("GET", url)

    def post(self, url, body=None):
        return self.req("POST", url, body if body is not None else {})

    def put(self, url, body):
        return self.req("PUT", url, body)

    def patch(self, url, body):
        return self.req("PATCH", url, body)

    def delete(self, url):
        return self.req("DELETE", url)


class Resp:
    def __init__(self, status, js, headers):
        self.status, self.js, self.headers = status, js, headers

    @property
    def data(self):
        return self.js.get("data") if isinstance(self.js, dict) else None

    @property
    def code(self):
        return self.js.get("code") if isinstance(self.js, dict) else None

    @property
    def details(self):
        return self.js.get("details") if isinstance(self.js, dict) else None

    def __repr__(self):
        return "%s %s" % (self.status, json.dumps(self.js, ensure_ascii=False)[:300])


PASSWORD = "Aim2day!2026"
CREATED_EMAILS = []


def new_user(tag="e"):
    """review user-service로 가입·로그인한 Client를 돌려준다(메일은 로그 모드)."""
    c = Client()
    c.get(USER + "/api/auth/csrf")
    email = "%s%d%03d@test.aim2day.dev" % (tag, int(time.time()), len(CREATED_EMAILS))
    masked = re.sub(r"^(.).*@", r"\1***@", email)
    r = c.post(USER + "/api/auth/email-codes", {"email": email})
    assert r.status == 200, r
    code = None
    for _ in range(20):
        logs = subprocess.run(["docker", "logs", "aim2day-user-service-review"], capture_output=True, text=True)
        lines = [l for l in (logs.stdout + logs.stderr).splitlines() if ("[MAIL] to=%s purpose=signup-code" % masked) in l]
        if lines:
            code = re.search(r"code=(\d{6})", lines[-1]).group(1)
            break
        time.sleep(0.3)
    r = c.post(USER + "/api/auth/email-codes/verify", {"email": email, "code": code})
    tk = r.data["signupToken"]
    r = c.post(USER + "/api/auth/signup", {"signupToken": tk, "username": "e2e", "password": PASSWORD, "passwordConfirm": PASSWORD})
    assert r.status == 201, r
    r = c.post(USER + "/api/auth/login", {"email": email, "password": PASSWORD})
    assert r.status == 200, r
    CREATED_EMAILS.append(email)
    c.user_id = int(sql("SELECT id FROM users WHERE email='%s'" % email))
    return c


def isolation_check(c):
    """시험용 세션으로 운영 프록시(goal-service)를 부르면 401이어야 한다(Redis 분리 확인)."""
    r = c.get("http://localhost/api/settings")
    ok(r.status == 401, "review session rejected by production (Redis isolated)", r.status)


def parallel(fns):
    """함수들을 동시에 시작해 결과 목록을 돌려준다(Barrier로 출발을 맞춘다)."""
    barrier = threading.Barrier(len(fns))
    results = [None] * len(fns)

    def run(i, f):
        barrier.wait()
        results[i] = f()

    ts = [threading.Thread(target=run, args=(i, f)) for i, f in enumerate(fns)]
    for t in ts:
        t.start()
    for t in ts:
        t.join()
    return results


def finish():
    """테스트 사용자 정리(시험용 DB). FK를 켠 채 자식 테이블부터 여러 번 시도해 CASCADE·RESTRICT를 그대로 따른다."""
    for e in CREATED_EMAILS:
        uid = sql("SELECT id FROM users WHERE email='%s'" % e)
        if not uid:
            continue
        tables = [t for t in sql("SELECT table_name FROM information_schema.columns WHERE table_schema='aim2day_review' AND column_name='user_id'").split() if t != "users"]
        for _ in range(len(tables) + 1):
            for t in tables:
                subprocess.run(DB_ARGS + ["DELETE FROM %s WHERE user_id=%s" % (t, uid)], capture_output=True)
            r = subprocess.run(DB_ARGS + ["DELETE FROM users WHERE id=%s" % uid], capture_output=True, text=True)
            if r.returncode == 0:
                break
    left = sql("SELECT COUNT(*) FROM users WHERE email LIKE '%%@test.aim2day.dev'")
    print("leftover test users:", left)
    print("RESULT pass=%d fail=%d" % (PASS, FAIL))
    if FAIL:
        raise SystemExit(1)
