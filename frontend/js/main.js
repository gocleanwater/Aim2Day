// 앱 진입점: 헤더(내비·계정 영역) 연결, 401 처리 등록, 라우터 시작.
import { setUnauthenticatedHandler } from "./api.js";
import { clearUser, getUser, logout, onUserChange } from "./auth.js";
import { navigate, parseHash, PUBLIC_PATHS, rememberReturnTo } from "./navigation.js";
import { startRouter } from "./router.js";
import { handleApiError, toast } from "./ui.js";

// ---------- 헤더: 로그인 상태에 따라 메뉴·계정 영역 전환 ----------
const mainNav = document.getElementById("main-nav");
const memberActions = document.getElementById("member-actions");
const guestActions = document.getElementById("guest-actions");
const memberName = document.getElementById("member-name");

/**
 * 사용자 상태(auth.js)를 헤더 DOM에 반영한다. 로그인이면 메뉴·계정 영역을, 아니면 로그인·가입 안내만 보인다.
 * onUserChange로 구독해 두었으므로 로그인·로그아웃·세션 만료 어느 경로로 상태가 바뀌어도 헤더가 같이 바뀐다.
 */
function renderHeader(user) {
  const loggedIn = Boolean(user);
  mainNav.hidden = !loggedIn;
  memberActions.hidden = !loggedIn;
  guestActions.hidden = loggedIn;
  // textContent로 넣어 사용자명에 HTML이 들어 있어도 해석되지 않게 한다.
  memberName.textContent = loggedIn ? `${user.username}님` : "";
}
onUserChange(renderHeader);
renderHeader(getUser());

document.getElementById("logout-button").addEventListener("click", async () => {
  try {
    await logout();
  } catch (error) {
    // logout()은 실패해도 화면은 로그인으로 보낸다. 여기서는 서버 오류만 알려 준다.
    handleApiError(error);
  }
});

// ---------- 401 처리 ----------
// 세션이 만료됐거나 없을 때 api.js가 부른다.
// 원래 보던 해시를 기억해 두었다가 로그인에 성공하면 그곳으로 돌려보낸다(auth.routeAfterLogin).
setUnauthenticatedHandler(() => {
  const wasLoggedIn = Boolean(getUser());
  clearUser();
  const { path } = parseHash();
  if (PUBLIC_PATHS.has(path)) return; // 이미 로그인 관련 화면이면 이동·기억할 것이 없다
  rememberReturnTo();
  if (wasLoggedIn) toast("로그인이 만료됐어요. 다시 로그인해 주세요.");
  navigate("#/login");
});

// 헤더와 401 처리 연결을 마친 뒤에 라우터를 시작한다. 첫 화면 요청이 401을 받아도 핸들러가 이미 등록돼 있어야 하기 때문이다.
startRouter();
