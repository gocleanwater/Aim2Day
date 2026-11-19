// 비밀번호 규칙 표시(회원가입·비밀번호 재설정 공통, 화면 흐름 문서 "회원가입").
// 서버가 최종 판정하고, 여기서는 입력 중 충족 여부를 보여 주고 제출 버튼을 잠그는 용도다.

const RULES = [
  ["length", "8~64자"],
  ["upper", "대문자"],
  ["lower", "소문자"],
  ["digit", "숫자"],
  ["symbol", "기호"],
];

export function evaluatePassword(password) {
  const result = {
    length: password.length >= 8 && password.length <= 64,
    upper: /[A-Z]/.test(password),
    lower: /[a-z]/.test(password),
    digit: /[0-9]/.test(password),
    // 기호 = 영문·숫자가 아닌 출력 가능한 ASCII(!~)
    symbol: /[\x21-\x2F\x3A-\x40\x5B-\x60\x7B-\x7E]/.test(password),
    // 출력 가능한 ASCII(!~) 밖의 문자(공백·한글·이모지 등)가 하나라도 있으면 금지
    illegal: /[^\x21-\x7E]/.test(password),
  };
  result.ok = RULES.every(([key]) => result[key]) && !result.illegal;
  return result;
}

/**
 * 입력칸 아래에 다섯 조건 체크리스트와 금지 문자 안내를 만들고, 입력할 때마다 갱신한다.
 * @param {HTMLInputElement} input 비밀번호 입력칸
 * @param {HTMLElement} container 체크리스트를 넣을 자리
 * @param {(ok:boolean)=>void} onChange 충족 여부가 갱신될 때 부른다(제출 버튼 활성화용)
 */
export function attachPasswordChecklist(input, container, onChange) {
  container.innerHTML = `
    <ul class="checklist" aria-label="비밀번호 조건"></ul>
    <p class="field-error" role="status" data-illegal hidden>영문, 숫자, 기호만 쓸 수 있습니다.</p>`;
  const list = container.querySelector("ul");
  const illegalEl = container.querySelector("[data-illegal]");
  for (const [key, label] of RULES) {
    const li = document.createElement("li");
    li.dataset.rule = key;
    li.innerHTML = `<span></span><span class="sr-only"></span>`;
    li.firstChild.textContent = label;
    list.append(li);
  }

  const update = () => {
    const state = evaluatePassword(input.value);
    for (const [key] of RULES) {
      const li = list.querySelector(`[data-rule="${key}"]`);
      li.classList.toggle("ok", state[key]);
      // 색·기호만으로 전달하지 않도록 스크린리더용 텍스트를 함께 갱신한다.
      li.lastChild.textContent = state[key] ? " 충족" : " 미충족";
    }
    illegalEl.hidden = !state.illegal;
    onChange(state.ok);
  };
  input.addEventListener("input", update);
  update();
  return update;
}
