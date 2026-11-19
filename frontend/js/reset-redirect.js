// 메일 링크는 명세(API §3.1)대로 `{BASE_URL}/reset-password.html#token={token}` 모양이다.
// 앱은 한 페이지(index.html) 해시 라우팅이므로, 이 얇은 페이지가 토큰 프래그먼트를 그대로 들고
// `/#/reset-password#token=...` 로 넘겨 준다. 프래그먼트는 서버로 전송되지 않으므로
// 토큰이 Nginx 접근 로그나 Referer에 남지 않는다. (이동에는 replace를 써서 이 중간 페이지를 기록에서 지운다.)
(function () {
  var fragment = location.hash.replace(/^#/, "");
  location.replace("/#/reset-password" + (fragment ? "#" + fragment : ""));
})();
