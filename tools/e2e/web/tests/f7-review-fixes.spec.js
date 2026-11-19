// Codex 1차 리뷰 반영 확인: 온보딩 자동 저장·나중에 하기, 사분면 마지막 위치 저장, 주간 색 수정, 세션 만료 시 모달 정리
const { test, expect } = require('@playwright/test');
const { signupViaApi, sql, PASSWORD } = require('../helpers');

async function login(page, email) {
  await page.goto('/#/login');
  await page.getByTestId('login-email').fill(email);
  await page.getByTestId('login-password').fill(PASSWORD);
  await page.getByTestId('login-submit').click();
}

async function api(page, method, url, data) {
  const xsrf = (await page.context().cookies()).find((c) => c.name === 'XSRF-TOKEN').value;
  const r = await page.request[method](url, { data, headers: { 'X-XSRF-TOKEN': xsrf } });
  if (!r.ok()) throw new Error(`${method} ${url} ${r.status()} ${await r.text()}`);
  return (await r.json()).data;
}

test('onboarding autosave without next, later goes to dashboard with resume banner', async ({ page, request }) => {
  const email = await signupViaApi(request, 'a');
  const uid = sql(`SELECT id FROM users WHERE email='${email}'`);
  await login(page, email);
  await expect(page).toHaveURL(/#\/onboarding/);

  await test.step('다음을 누르지 않아도 입력이 자동 저장되고 새로고침 뒤 남는다', async () => {
    await page.getByTestId('yearly-title-0').fill('자동 저장 목표');
    await expect.poll(() => sql(`SELECT COUNT(*) FROM onboarding_drafts WHERE user_id=${uid} AND draft_data LIKE '%자동 저장 목표%'`), { timeout: 10000 }).toBe('1');
    await page.reload();
    await expect(page.getByTestId('yearly-title-0')).toHaveValue('자동 저장 목표');
  });

  await test.step('나중에 하기 → 대시보드에 머물고 이어서 작성하기 안내 → 누르면 온보딩', async () => {
    await page.getByTestId('onboarding-later').click();
    await expect(page).toHaveURL(/#\/dashboard/);
    await expect(page.getByTestId('onboarding-banner')).toBeVisible();
    await page.getByTestId('onboarding-resume').click();
    await expect(page).toHaveURL(/#\/onboarding/);
    await expect(page.getByTestId('yearly-title-0')).toHaveValue('자동 저장 목표');
  });
});

test('quadrant keeps the last position, weekly color edit, modal closes on session expiry', async ({ page, request }) => {
  const email = await signupViaApi(request, 'b');
  const uid = sql(`SELECT id FROM users WHERE email='${email}'`);
  await login(page, email);
  await expect(page).toHaveURL(/#\/onboarding/);
  const today = (await (await page.request.get('/api/settings')).json()).data.today;
  const ids = await api(page, 'post', '/api/goals/onboarding/complete', {
    yearlyGoals: [{ tempId: 'y', title: '백엔드' }],
    monthlyGoals: [{ tempId: 'm', yearlyGoalTempId: 'y', title: '알고리즘' }],
    weeklyGoals: [{ tempId: 'w', monthlyGoalTempId: 'm', title: '문제 풀기' }],
    actions: [{ type: 'MAINTENANCE', title: '장보기', performedDate: today }],
  });
  const actionId = sql(`SELECT id FROM actions WHERE user_id=${uid}`);

  await test.step('사분면: 첫 저장이 늦게 오는 사이 다른 칸으로 옮기면 마지막 칸이 저장된다', async () => {
    let first = true;
    await page.route('**/placement**', async (route) => {
      if (first && route.request().method() !== 'GET') { first = false; await new Promise((r) => setTimeout(r, 1500)); }
      await route.continue();
    });
    await page.goto('/#/dashboard');
    await page.getByTestId('point-' + actionId).click();
    await page.getByTestId('quadrant-Q1').click();
    await page.waitForTimeout(600); // 첫 저장 요청이 나가 지연 중인 상태
    await page.getByTestId('quadrant-Q3').click();
    await expect.poll(() => sql(`SELECT CONCAT(ROUND(pending_importance_x),',',ROUND(pending_urgency_y)) FROM action_quadrant_placements WHERE action_id=${actionId}`), { timeout: 10000 }).toBe('25,25');
    await page.unroute('**/placement**');
  });

  await test.step('주간 목표 색 직접 지정 → 저장, 해제 → 상속(null)', async () => {
    const wid = ids.weeklyGoals.w;
    await page.goto('/#/plan');
    await page.getByTestId('weekly-edit-' + wid).click();
    await page.getByTestId('weekly-edit-use-color').check();
    await page.getByTestId('weekly-edit-color').fill('#123456');
    await page.getByTestId('weekly-edit-save').click();
    await expect.poll(() => sql(`SELECT UPPER(color) FROM weekly_goals WHERE id=${wid}`)).toBe('#123456');
    await page.getByTestId('weekly-edit-' + wid).click();
    await page.getByTestId('weekly-edit-use-color').uncheck();
    await page.getByTestId('weekly-edit-save').click();
    await expect.poll(() => sql(`SELECT COALESCE(color,'NULL') FROM weekly_goals WHERE id=${wid}`)).toBe('NULL');
  });

  await test.step('달력 수정 모달에서 세션이 끊긴 채 저장 → 로그인 화면, 모달 없음', async () => {
    await api(page, 'post', '/api/calendar/items', { itemType: 'TASK', title: '과제', startDate: today });
    const itemId = sql(`SELECT id FROM calendar_items WHERE user_id=${uid}`);
    await page.goto('/#/calendar');
    await page.getByTestId('cal-day-' + today).click();
    await page.getByTestId('item-edit-' + itemId).click();
    await expect(page.getByTestId('item-edit-dialog')).toBeVisible();
    await page.context().clearCookies({ name: 'SESSION' });
    await page.getByTestId('item-edit-title').fill('과제 2');
    await page.getByTestId('item-edit-save').click();
    await expect(page).toHaveURL(/#\/login/);
    await expect(page.locator('dialog[open]')).toHaveCount(0);
    await expect(page.getByTestId('login-email')).toBeVisible();
  });
});
