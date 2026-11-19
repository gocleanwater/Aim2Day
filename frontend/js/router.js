// 해시 라우터. 주소의 `#/경로`를 보고 화면 모듈을 #view 안에 그린다.
//
// 화면 모듈 규약: `export function render(container, route)`
//   - route: {path, query, fragment, isActive()} — isActive()는 await 뒤에 "아직 이 화면인가"를 확인하는 용도
//   - 반환값(선택): 화면을 떠날 때 부를 정리 함수(타이머 해제 등)
// 새 화면은 아래 ROUTES에 화면 모듈을 등록하면 된다.
import { ensureUser, routeAfterLogin } from "./auth.js";
import { navigate, parseHash, PUBLIC_PATHS } from "./navigation.js";
import { closeAllModals, handleApiError } from "./ui.js";
import * as login from "./views/login.js";
import * as signup from "./views/signup.js";
import * as forgotPassword from "./views/forgotPassword.js";
import * as resetPassword from "./views/resetPassword.js";
import * as settings from "./views/settings.js";
import * as onboarding from "./views/onboarding.js";
import * as dashboard from "./views/dashboard.js";
import * as goals from "./views/goals.js";
import * as goalDetail from "./views/goalDetail.js";
import * as trash from "./views/trash.js";
import * as plan from "./views/plan.js";
import * as calendar from "./views/calendar.js";
import * as reports from "./views/reports.js";

const ROUTES = {
  "/login": { title: "로그인", view: login, nav: null },
  "/signup": { title: "회원가입", view: signup, nav: null },
  "/forgot-password": { title: "비밀번호 재설정 요청", view: forgotPassword, nav: null },
  "/reset-password": { title: "비밀번호 재설정", view: resetPassword, nav: null },
  "/settings": { title: "설정", view: settings, nav: "settings" },
  "/onboarding": { title: "목표 세우기", view: onboarding, nav: null },
  "/dashboard": { title: "대시보드", view: dashboard, nav: "dashboard" },
  "/plan": { title: "월간·주간 목표 설정", view: plan, nav: null },
  "/calendar": { title: "달력", view: calendar, nav: "calendar" },
  "/goals": { title: "연간 목표", view: goals, nav: "goals" },
  "/goals/trash": { title: "삭제된 목표", view: trash, nav: "goals" },
  "/reports": { title: "리포트", view: reports, nav: "reports" },
};

let cleanup = null;
// 빠르게 연속 이동할 때 늦게 끝난 이전 이동이 화면을 덮어쓰지 않도록 이동마다 번호를 매긴다.
let navigationId = 0;

async function dispatch() {
  const id = ++navigationId;
  const isActive = () => id === navigationId;
  const view = document.getElementById("view");
  const { path, query, fragment } = parseHash();

  if (typeof cleanup === "function") cleanup();
  cleanup = null;
  closeAllModals(); // 수정·확인 모달은 #view 밖에 있으므로 화면 이동 때 함께 닫는다(401 이동 포함)

  // 고정 경로 먼저, 없으면 #/goals/{id} 같은 동적 경로(숫자 id)를 찾는다.
  let params = {};
  let route = ROUTES[path];
  const goalMatch = /^\/goals\/(\d+)$/.exec(path);
  if (!route && goalMatch) {
    route = { title: "연간 목표 상세", view: goalDetail, nav: "goals" };
    params = { id: goalMatch[1] };
  }
  if (!route) {
    // 알 수 없는 주소(빈 해시 포함)는 기본 화면으로. 로그인이 안 돼 있으면 아래 보호 로직이 로그인으로 보낸다.
    navigate("#/dashboard", { replace: true });
    return;
  }

  try {
    if (!PUBLIC_PATHS.has(path)) {
      // 로그인 필요 화면: 세션이 없으면 api.js의 401 처리가 로그인 화면으로 보내고(돌아올 해시 기억), 여기서는 멈춘다.
      const user = await ensureUser();
      if (!isActive() || !user) return;
    } else if (path === "/login") {
      // 이미 로그인돼 있으면 로그인 화면 대신 로그인 뒤 분기로 보낸다. 조용히 확인하므로 401로 이동시키지 않는다.
      const user = await ensureUser({ redirectOn401: false });
      if (!isActive()) return;
      if (user) {
        await routeAfterLogin();
        return;
      }
    }
  } catch (error) {
    if (isActive()) handleApiError(error);
    return;
  }

  document.title = `${route.title} · Aim2Day`;
  markCurrentNav(route.nav);
  view.innerHTML = "";
  cleanup = route.view.render(view, { path, query, fragment, isActive, params }) || null;
  view.focus({ preventScroll: true });
  window.scrollTo(0, 0);
}

/** 상단 메뉴에서 현재 화면에 aria-current="page"를 붙인다. */
function markCurrentNav(navKey) {
  document.querySelectorAll("[data-nav]").forEach((el) => {
    if (el.dataset.nav === navKey) el.setAttribute("aria-current", "page");
    else el.removeAttribute("aria-current");
  });
}

export function startRouter() {
  window.addEventListener("hashchange", dispatch);
  dispatch();
}

