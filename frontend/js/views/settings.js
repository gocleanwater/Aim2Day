// 설정 (#/settings). GET /api/users/me(기본 정보), GET /api/settings, PUT /api/settings
// 주 시작 요일과 시간대. PUT에는 조회 때 받은 version을 함께 보낸다(동시 수정 방지, API §2.4).
import { api } from "../api.js";
import { ensureUser } from "../auth.js";
import { setAccountTimeZone } from "./goalCommon.js";
import { clearFormErrors, formatDate, handleApiError, renderLoading, setBusy, toast } from "../ui.js";

// 주 시작 요일 선택지. 값은 서버 enum(weekStartDay)과 같고 달력의 첫 열을 정한다.
const DAYS = [
  ["SUNDAY", "일요일"],
  ["MONDAY", "월요일"],
  ["TUESDAY", "화요일"],
  ["WEDNESDAY", "수요일"],
  ["THURSDAY", "목요일"],
  ["FRIDAY", "금요일"],
  ["SATURDAY", "토요일"],
];

/** 시간대 선택지. 브라우저가 아는 IANA 이름을 쓰고, 현재 저장값이 목록에 없으면 맨 앞에 넣어 값이 사라지지 않게 한다. */
function timezoneChoices(current) {
  let list =
    typeof Intl.supportedValuesOf === "function"
      ? Intl.supportedValuesOf("timeZone")
      : ["Asia/Seoul", "Asia/Tokyo", "America/New_York", "America/Los_Angeles", "Europe/London", "Europe/Paris"];
  if (!list.includes("UTC")) list = [...list, "UTC"]; // 일부 브라우저 목록에는 UTC가 없다(서버 ZoneId는 허용)
  if (current && !list.includes(current)) list = [current, ...list];
  return list;
}

// 화면 진입점. 사용자 기본 정보와 설정을 함께 읽어 폼에 채운다. 저장은 PUT 한 번으로 요일·시간대를 같이 보내고,
// 성공하면 응답(새 version 포함)을 설정으로 삼아 화면을 다시 그린다. 시간대는 서버의 "오늘" 계산 기준이므로 저장 직후 캐시도 바꾼다.
export function render(container, route) {
  renderLoading(container);

  // 마지막으로 받은 서버 상태. 저장 때 version을 여기서 꺼낸다.
  let settings = null;
  let form = null;
  let saveButton = null;

  // 사용자 정보(ensureUser)와 설정을 병렬로 읽어 그린다. 409 뒤 최신 값 반영에도 같은 함수를 쓴다.
  async function load() {
    const [user, loaded] = await Promise.all([ensureUser(), api.get("/api/settings")]);
    if (!route.isActive()) return;
    settings = loaded;
    draw(user);
  }

  /** 서버에서 받은 값으로 화면 전체를 다시 그린다(처음 로드, 저장 성공, 409 뒤 최신값 반영에 공통). */
  function draw(user) {
    container.innerHTML = `
      <h1 class="page-title">설정</h1>
      <section class="card">
        <dl class="info-list" data-testid="settings-info">
          <dt>사용자명</dt><dd data-user-name></dd>
          <dt>이메일</dt><dd data-user-email></dd>
        </dl>
        <form id="settings-form" novalidate>
          <p class="form-error" data-form-error role="alert" hidden></p>
          <fieldset>
            <legend>주 시작 요일</legend>
            <div class="radio-grid" role="radiogroup" data-testid="week-start-day"></div>
            <p class="field-error" data-error-for="weekStartDay"></p>
          </fieldset>
          <div class="field">
            <label for="settings-timezone">시간대</label>
            <select id="settings-timezone" name="timezone" data-testid="timezone"></select>
            <p class="field-hint">오늘·마감일·리포트 기간에 적용됩니다.</p>
            <p class="field-error" data-error-for="timezone"></p>
          </div>
          <p class="note">기본값은 일요일입니다. 선택한 요일을 주간 계획·오늘·달력의 주 경계에 함께 적용합니다. 이미 만든 주간 목표의 기간은 바뀌지 않습니다.</p>
          <p class="note" data-testid="settings-today"></p>
          <button id="settings-save" type="submit" class="btn" data-testid="settings-save">저장</button>
        </form>
        <p class="note" style="margin-top:20px">AI / BYOK / 결제: 해당 기능은 현재 개발 중입니다.</p>
      </section>`;

    // 사용자 입력이 섞인 값은 textContent로만 넣는다.
    container.querySelector("[data-user-name]").textContent = user?.username ?? "";
    container.querySelector("[data-user-email]").textContent = user?.email ?? "";

    form = container.querySelector("#settings-form");
    saveButton = container.querySelector("#settings-save");

    const group = container.querySelector(".radio-grid");
    for (const [value, label] of DAYS) {
      const item = document.createElement("label");
      const radio = document.createElement("input");
      radio.type = "radio";
      radio.name = "weekStartDay";
      radio.value = value;
      radio.checked = value === settings.weekStartDay;
      item.append(radio, document.createTextNode(label));
      group.append(item);
    }

    const select = form.timezone;
    for (const zone of timezoneChoices(settings.timezone)) {
      const option = document.createElement("option");
      option.value = zone;
      option.textContent = zone;
      select.append(option);
    }
    select.value = settings.timezone;

    // 서버가 계정 시간대로 계산한 "오늘"을 보여 줘 저장 효과를 바로 확인할 수 있게 한다.
    container.querySelector("[data-testid=settings-today]").textContent = `서버 기준 오늘: ${formatDate(settings.today)}`;

    form.addEventListener("submit", save);
  }

  // 설정 저장. 조회 때 받은 version을 함께 보내 다른 곳에서 먼저 바뀐 경우를 서버가 409 VERSION_CONFLICT로 알려 준다.
  async function save(event) {
    event.preventDefault();
    clearFormErrors(form);
    const weekStartDay = form.querySelector('input[name="weekStartDay"]:checked')?.value;
    const timezone = form.timezone.value;
    setBusy(saveButton, true, "저장 중…");
    try {
      settings = await api.put("/api/settings", {
        body: { weekStartDay, timezone, version: settings.version },
      });
      setAccountTimeZone(settings.timezone); // 목표 화면의 날짜 표시(복구 기한 등)가 새 시간대를 따르게 한다
      if (!route.isActive()) return;
      const user = await ensureUser();
      if (!route.isActive()) return; // 사용자 정보를 기다리는 사이 화면을 떠났다면 공유 #view를 다시 그리지 않는다
      draw(user);
      toast("설정을 저장했어요.");
    } catch (error) {
      setBusy(saveButton, false);
      // 409 VERSION_CONFLICT: 다른 곳에서 먼저 바뀐 것이므로 안내 뒤 최신 값으로 화면을 다시 채운다.
      handleApiError(error, {
        form,
        onVersionConflict: () => load().catch((e) => handleApiError(e)),
      });
    }
  }

  load().catch((error) => {
    if (!route.isActive()) return;
    container.innerHTML = `<h1 class="page-title">설정</h1><p class="form-error" role="alert"></p>`;
    container.querySelector(".form-error").textContent = error.message || "설정을 불러오지 못했어요.";
    handleApiError(error);
  });
}
