// 공통 fetch 래퍼. 모든 화면은 fetch를 직접 부르지 않고 이 모듈만 거친다.
//
// 책임
//  1. 같은 출처(상대 경로 /api/...)로만 호출한다. 프록시가 한 출처로 묶어 주므로 CORS를 가정하지 않는다.
//  2. 응답 본문 {code, message, data, details}를 풀어서 성공이면 data를, 실패면 ApiError를 던진다.
//     분기는 HTTP 상태나 message가 아니라 code로 한다(API §2.1).
//  3. CSRF: 변경 요청(POST·PUT·PATCH·DELETE)에는 XSRF-TOKEN 쿠키 값을 X-XSRF-TOKEN 헤더로 보낸다.
//     쿠키가 없으면 GET /api/auth/csrf로 먼저 받고, 403 CSRF_TOKEN_INVALID면 재발급 후 딱 한 번만 재시도한다.
//  4. 401 UNAUTHENTICATED면 등록된 핸들러(로그인 화면으로 이동 + 돌아올 해시 기억)를 부른다.

const CSRF_COOKIE = "XSRF-TOKEN";
const CSRF_HEADER = "X-XSRF-TOKEN";
const MUTATING_METHODS = new Set(["POST", "PUT", "PATCH", "DELETE"]);

/** 서버가 준 오류 응답(또는 네트워크 실패)을 나타낸다. 화면은 code로 분기하고 message를 그대로 보여 준다. */
export class ApiError extends Error {
  constructor(status, code, message, details = null) {
    super(message);
    this.name = "ApiError";
    this.status = status; // HTTP 상태. 네트워크 실패는 0
    this.code = code; // 분기 기준. 서버 코드 외에 클라이언트가 만든 NETWORK_ERROR, INVALID_RESPONSE가 있다
    this.details = details; // 서버 details(없으면 null)
  }
}

// ---------- 401 처리 ----------
let unauthenticatedHandler = null;

/** 401 UNAUTHENTICATED일 때 부를 함수를 등록한다. 순환 import를 피하려고 라우터가 아니라 main.js가 연결한다. */
export function setUnauthenticatedHandler(handler) {
  unauthenticatedHandler = handler;
}

// ---------- CSRF ----------
export function readCookie(name) {
  for (const part of document.cookie.split("; ")) {
    const index = part.indexOf("=");
    if (index > 0 && part.slice(0, index) === name) {
      return decodeURIComponent(part.slice(index + 1));
    }
  }
  return null;
}

// 동시에 여러 요청이 CSRF 발급을 원해도 /api/auth/csrf는 한 번만 부른다.
let csrfInFlight = null;

function fetchCsrf() {
  if (!csrfInFlight) {
    csrfInFlight = send("GET", "/api/auth/csrf").finally(() => {
      csrfInFlight = null;
    });
  }
  return csrfInFlight;
}

/** 현재 쓸 CSRF 토큰. 로그인 직후 값이 바뀔 수 있어 요청마다 쿠키의 현재 값을 읽고, 없을 때만 새로 받는다. */
async function currentCsrfToken() {
  let token = readCookie(CSRF_COOKIE);
  if (!token) {
    await fetchCsrf();
    token = readCookie(CSRF_COOKIE);
  }
  return token;
}

// ---------- 전송 ----------
const NETWORK_MESSAGE = "서버에 연결할 수 없어요. 잠시 후 다시 시도해 주세요.";

/** 요청 한 번을 보내고 응답을 푼다. 재시도·401 처리는 request()가 맡는다. */
async function send(method, url, body, csrfToken, keepalive = false) {
  const headers = { Accept: "application/json" };
  if (body !== undefined) headers["Content-Type"] = "application/json";
  if (csrfToken) headers[CSRF_HEADER] = csrfToken;

  let response;
  try {
    response = await fetch(url, {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
      credentials: "same-origin",
      keepalive, // 페이지가 닫히는 중에도 요청이 끝까지 가게 한다(임시 저장의 마지막 시도용, 본문 64KB 제한)
    });
  } catch {
    throw new ApiError(0, "NETWORK_ERROR", NETWORK_MESSAGE);
  }

  let payload = null;
  try {
    payload = await response.json();
  } catch {
    // 프록시의 502 HTML 페이지처럼 JSON이 아닌 응답은 아래에서 INVALID_RESPONSE로 처리한다.
  }

  if (payload && typeof payload.code === "string") {
    // 성공 여부는 HTTP 상태만 보지 않고 code를 먼저 확인한다.
    if (payload.code === "SUCCESS" && response.ok) {
      return payload.data === undefined ? null : payload.data;
    }
    throw new ApiError(
      response.status,
      payload.code,
      payload.message || "요청을 처리하지 못했어요.",
      payload.details === undefined ? null : payload.details,
    );
  }

  throw new ApiError(
    response.status,
    "INVALID_RESPONSE",
    `서버 응답을 처리하지 못했어요. (HTTP ${response.status})`,
  );
}

/**
 * 공통 요청 함수.
 * @param {string} method HTTP 메서드
 * @param {string} path /api/... 상대 경로
 * @param {object} [options]
 * @param {object} [options.body] JSON 본문
 * @param {object} [options.query] 쿼리 문자열(null·undefined 값은 생략)
 * @param {boolean} [options.keepalive=false] true면 fetch keepalive로 보낸다(화면을 떠나는 순간의 저장용)
 * @param {boolean} [options.redirectOn401=true] false면 401이어도 로그인 화면으로 보내지 않고 오류만 던진다
 *   (앱 시작 때 "로그인돼 있나?"를 조용히 확인하는 용도)
 */
export async function request(method, path, options = {}) {
  const { body, query, redirectOn401 = true, keepalive = false } = options;
  const verb = method.toUpperCase();
  const mutating = MUTATING_METHODS.has(verb);

  let url = path;
  if (query) {
    const params = new URLSearchParams();
    for (const [key, value] of Object.entries(query)) {
      if (value !== null && value !== undefined && value !== "") params.append(key, String(value));
    }
    const text = params.toString();
    if (text) url += `?${text}`;
  }

  try {
    try {
      return await send(verb, url, body, mutating ? await currentCsrfToken() : null, keepalive);
    } catch (error) {
      // 토큰이 만료·불일치일 수 있으므로 새로 받은 뒤 같은 요청을 "한 번만" 다시 보낸다.
      // 두 번째도 실패하면 그대로 던져 무한 재시도를 막는다.
      if (mutating && error instanceof ApiError && error.status === 403 && error.code === "CSRF_TOKEN_INVALID") {
        await fetchCsrf();
        return await send(verb, url, body, readCookie(CSRF_COOKIE), keepalive);
      }
      throw error;
    }
  } catch (error) {
    if (error instanceof ApiError && error.code === "UNAUTHENTICATED" && redirectOn401 && unauthenticatedHandler) {
      unauthenticatedHandler(error);
    }
    throw error;
  }
}

export const api = {
  get: (path, options) => request("GET", path, options),
  post: (path, options) => request("POST", path, options),
  put: (path, options) => request("PUT", path, options),
  patch: (path, options) => request("PATCH", path, options),
  delete: (path, options) => request("DELETE", path, options),
};
