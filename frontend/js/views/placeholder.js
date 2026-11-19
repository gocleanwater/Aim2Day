// 아직 구현하지 않은 화면의 자리. 상단 내비는 정상 동작하고 화면에는 "준비 중"만 보인다.
// 해당 단위(F2~F6)를 구현할 때 router.js의 ROUTES에서 이 자리를 실제 화면 모듈로 바꾼다.

export function placeholder(title, unit) {
  return {
    render(container) {
      container.innerHTML = `
        <h1 class="page-title"></h1>
        <section class="card placeholder" data-testid="placeholder">
          <p>준비 중입니다.</p>
        </section>`;
      container.querySelector("h1").textContent = title;
      container.querySelector("section").dataset.unit = unit;
    },
  };
}
