// 로그인 사용자 상태와 로그인 뒤 분기. 화면과 헤더가 같은 사용자 정보를 보도록 한 곳에서 관리한다.
import { api, ApiError } from "./api.js";
import { navigate, takeReturnTo, clearReturnTo } from "./navigation.js";

let currentUser = null;
let loading = null;
const listeners = new Set();

export function getUser() {
  return currentUser;
}

/** 사용자 상태가 바뀔 때(로그인·로그아웃·세션 만료) 헤더 같은 구독자가 다시 그려지게 한다. */
export function onUserChange(listener) {
  listeners.add(listener);
}

export function setUser(user) {
  currentUser = user;
  listeners.forEach((listener) => listener(user));
}

export function clearUser() {
  setUser(null);
}

/**
 * 세션이 유효한지 GET /api/users/me로 확인하고 사용자 정보를 채운다.
 * 이미 알고 있으면 다시 부르지 않고, 동시 호출은 하나로 합친다.
 * @param {{redirectOn401?: boolean}} [options] false면 401이어도 로그인 화면으로 보내지 않고 null만 돌려준다
 * @returns {Promise<object|null>} 로그인 안 됐으면 null
 */
export async function ensureUser({ redirectOn401 = true } = {}) {
  if (currentUser) return currentUser;
  if (!loading) {
    loading = api
      .get("/api/users/me", { redirectOn401 })
      .then((user) => {
        setUser(user);
        return user;
      })
      .catch((error) => {
        if (error instanceof ApiError && error.code === "UNAUTHENTICATED") return null;
        throw error;
      })
      .finally(() => {
        loading = null;
      });
  }
  return loading;
}

// ---------- 온보딩 "나중에 하기" ----------
// 사용자가 온보딩에서 [나중에 하기]를 눌렀다는 표시. 이 세션(탭) 동안만 유지한다.
// 표시가 있으면 대시보드는 온보딩으로 되돌려 보내지 않고, 작성하던 계획을 이어서 쓰라는 안내만 보여 준다.
// 이 표시가 없으면(로그인 직후, 주소창으로 직접 진입 등) needsOnboarding인 사용자는 온보딩으로 보낸다.
const DEFERRED_KEY = "aim2day.onboardingDeferred";

export function deferOnboarding() {
  sessionStorage.setItem(DEFERRED_KEY, "1");
}

export function isOnboardingDeferred() {
  return sessionStorage.getItem(DEFERRED_KEY) === "1";
}

export function clearOnboardingDeferred() {
  sessionStorage.removeItem(DEFERRED_KEY);
}

/**
 * 로그인 직후(또는 이미 로그인된 채 로그인 화면을 열었을 때) 갈 곳을 정한다.
 * 온보딩이 필요하면 항상 온보딩이 먼저다. 아니면 401 전에 보던 화면, 없으면 대시보드다.
 */
export async function routeAfterLogin() {
  // 새로 로그인한 사람은 "나중에 하기"를 눌렀던 이전 선택과 무관하게 온보딩부터 안내한다.
  clearOnboardingDeferred();
  const status = await api.get("/api/goals/onboarding/status");
  if (status.needsOnboarding) {
    // 온보딩을 끝내기 전에는 예전 화면으로 돌아가지 않는다.
    clearReturnTo();
    navigate("#/onboarding");
    return;
  }
  navigate(takeReturnTo() || "#/dashboard");
}

/** 로그아웃. 서버 호출이 실패해도 이 브라우저의 로그인 표시는 지우고 로그인 화면으로 보낸다. */
export async function logout() {
  // 사본 키의 사용자 식별자는 요청 전에 잡아 둔다. 로그아웃을 기다리는 사이 다른 요청이 401을 받으면 clearUser()가 먼저 돌아 식별자를 잃는다.
  const user = getUser();
  const owner = user ? String(user.email ?? user.userId ?? "") : "";
  // 명시적 로그아웃임을 온보딩 화면에 먼저 알린다. 화면이 자동 저장·사본 쓰기를 멈춘 뒤에 사본을 지워야
  // 화면 정리(flushOnLeave)가 사본을 다시 만들지 않는다. 401 세션 만료는 이 경로를 거치지 않아 사본이 남는다.
  window.dispatchEvent(new Event("aim2day:logout"));
  try {
    await api.post("/api/auth/logout", { redirectOn401: false });
  } finally {
    clearReturnTo();
    clearOnboardingDeferred();
    // 온보딩 미저장 입력 사본: 로그아웃을 시작한 사용자의 키와 옛 공용 키를 지운다.
    // (온보딩 화면 모듈은 auth.js를 import하므로, 순환을 피하려고 키 이름만 여기서 직접 조립한다.)
    try {
      if (owner) localStorage.removeItem(`aim2day.onboardingUnsaved:${owner}`);
      localStorage.removeItem("aim2day.onboardingUnsaved");
    } catch {
      /* 저장소를 못 쓰면 지울 것도 없다 */
    }
    clearUser();
    navigate("#/login");
  }
}
