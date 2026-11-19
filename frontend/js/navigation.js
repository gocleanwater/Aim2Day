// 해시 이동과 "로그인 뒤 돌아올 곳" 기억. router.js와 화면들이 함께 쓰므로 순환 import를 피해 따로 둔다.

// 로그인 없이 볼 수 있는 화면. 나머지는 모두 로그인이 필요하다.
export const PUBLIC_PATHS = new Set(["/login", "/signup", "/forgot-password", "/reset-password"]);

const RETURN_KEY = "aim2day.returnTo";

/**
 * 현재 주소의 해시를 분해한다.
 * 비밀번호 재설정 링크는 `#/reset-password#token=...` 처럼 해시 안에 한 번 더 `#`가 온다.
 * 첫 `#` 뒤는 경로(+쿼리), 두 번째 `#` 뒤는 fragment(토큰)로 나눈다.
 */
export function parseHash(hash = location.hash) {
  const raw = hash.replace(/^#/, "");
  const split = raw.indexOf("#");
  const main = split >= 0 ? raw.slice(0, split) : raw;
  const fragment = split >= 0 ? raw.slice(split + 1) : "";
  const queryAt = main.indexOf("?");
  const path = queryAt >= 0 ? main.slice(0, queryAt) : main;
  const query = new URLSearchParams(queryAt >= 0 ? main.slice(queryAt + 1) : "");
  return { path: path || "/", query, fragment: new URLSearchParams(fragment) };
}

/** 해시를 바꾼다. 같은 해시면 hashchange가 안 오므로 직접 이벤트를 보내 화면을 다시 그린다. */
export function navigate(hash, { replace = false } = {}) {
  if (replace) {
    history.replaceState(null, "", hash);
    window.dispatchEvent(new HashChangeEvent("hashchange"));
  } else if (location.hash === hash) {
    window.dispatchEvent(new HashChangeEvent("hashchange"));
  } else {
    location.hash = hash;
  }
}

/** 401로 로그인 화면에 보낼 때 원래 가던 곳을 기억한다. 로그인 관련 화면은 기억하지 않는다. */
export function rememberReturnTo(hash = location.hash) {
  const { path } = parseHash(hash);
  if (!hash.startsWith("#/") || PUBLIC_PATHS.has(path)) return;
  sessionStorage.setItem(RETURN_KEY, hash);
}

/** 기억한 곳을 꺼내고 지운다. 없으면 null. */
export function takeReturnTo() {
  const hash = sessionStorage.getItem(RETURN_KEY);
  sessionStorage.removeItem(RETURN_KEY);
  return hash;
}

export function clearReturnTo() {
  sessionStorage.removeItem(RETURN_KEY);
}
