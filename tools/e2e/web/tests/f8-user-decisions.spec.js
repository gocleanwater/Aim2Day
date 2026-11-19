// 사용자 결정 반영 확인: 온보딩 저장 충돌 시 내 입력 지키기, 사분면 전체 변경 취소, 주간 후보가 없으면 행동 추가 막기, 한국어 월 선택기
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

test('onboarding conflict keeps my input and lets me choose', async ({ page, request }) => {
  const email = await signupViaApi(request, 'k');
  const uid = sql(`SELECT id FROM users WHERE email='${email}'`);
  await login(page, email);
  await expect(page).toHaveURL(/#\/onboarding/);
  await page.getByTestId('yearly-title-0').fill('내 목표');
  await expect.poll(() => sql(`SELECT COUNT(*) FROM onboarding_drafts WHERE user_id=${uid}`), { timeout: 10000 }).toBe('1');
  await expect(page.getByTestId('onboarding-save-status')).toContainText('저장됨');

  // 다른 탭이 먼저 저장한 상황을 API로 만든다.
  const latest = await api(page, 'get', '/api/goals/onboarding/draft');
  const other = { ...latest.draftData, yearlyGoals: [{ ...latest.draftData.yearlyGoals[0], title: '다른 탭 목표' }] };
  await api(page, 'put', '/api/goals/onboarding/draft', { currentStep: 1, draftData: other, version: latest.version });

  await page.getByTestId('yearly-title-0').fill('내 목표 고침');
  await expect(page.getByTestId('onboarding-conflict')).toBeVisible({ timeout: 10000 });
  await expect(page.getByTestId('yearly-title-0')).toHaveValue('내 목표 고침');
  await page.getByTestId('conflict-keep-mine').click();
  await expect(page.getByTestId('onboarding-conflict')).toHaveCount(0);
  await expect.poll(() => sql(`SELECT COUNT(*) FROM onboarding_drafts WHERE user_id=${uid} AND draft_data LIKE '%내 목표 고침%'`), { timeout: 10000 }).toBe('1');
});

test('quadrant cancel all, need-weekly block, month picker', async ({ page, request }) => {
  const email = await signupViaApi(request, 'n');
  const uid = sql(`SELECT id FROM users WHERE email='${email}'`);
  await login(page, email);
  await expect(page).toHaveURL(/#\/onboarding/);
  const settings = (await (await page.request.get('/api/settings')).json()).data;
  const today = settings.today;
  const ids = await api(page, 'post', '/api/goals/onboarding/complete', {
    yearlyGoals: [{ tempId: 'y', title: '백엔드' }],
    monthlyGoals: [{ tempId: 'm', yearlyGoalTempId: 'y', title: '알고리즘' }, { tempId: 'm2', yearlyGoalTempId: 'y', title: '주간 없는 월간' }],
    weeklyGoals: [{ tempId: 'w', monthlyGoalTempId: 'm', title: '문제 풀기' }],
    actions: [{ type: 'MAINTENANCE', title: '장보기', performedDate: today }, { type: 'MAINTENANCE', title: '빨래', performedDate: today }],
  });
  const [a1, a2] = sql(`SELECT GROUP_CONCAT(id ORDER BY id) FROM actions WHERE user_id=${uid}`).split(',');

  await test.step('사분면: 두 점을 놓은 뒤 [변경 취소] 한 번으로 모두 미분류', async () => {
    await page.goto('/#/dashboard');
    await page.getByTestId('point-' + a1).click();
    await page.getByTestId('quadrant-Q1').click();
    await page.getByTestId('point-' + a2).click();
    await page.getByTestId('quadrant-Q3').click();
    await expect.poll(() => sql(`SELECT COUNT(*) FROM action_quadrant_placements WHERE action_id IN (${a1},${a2})`), { timeout: 10000 }).toBe('2');
    await page.getByTestId('quad-cancel').click();
    const dialog = page.getByRole('dialog');
    if (await dialog.count()) await dialog.getByRole('button', { name: /취소/ }).last().click().catch(() => {});
    await expect.poll(() => sql(`SELECT COUNT(*) FROM action_quadrant_placements WHERE action_id IN (${a1},${a2})`), { timeout: 10000 }).toBe('0');
  });

  await test.step('주간 목표가 없는 월간을 고르면 추가 버튼이 잠기고 안내가 보인다', async () => {
    await expect(page.getByTestId('add-action-destination').locator('option', { hasText: '주간 없는 월간' })).toHaveCount(1);
    const opts = await page.getByTestId('add-action-destination').locator('option').allTextContents();
    await page.getByTestId('add-action-destination').selectOption({ label: opts.find((o) => o.includes('주간 없는 월간')) });
    await expect(page.getByTestId('add-action-need-weekly')).toBeVisible();
    await expect(page.getByTestId('add-action-submit')).toBeDisabled();
    const opts2 = await page.getByTestId('add-action-destination').locator('option').allTextContents();
    await page.getByTestId('add-action-destination').selectOption({ label: opts2.find((o) => o.includes('알고리즘')) });
    await expect(page.getByTestId('add-action-need-weekly')).toBeHidden();
    await expect(page.getByTestId('add-action-submit')).toBeEnabled();
  });

  await test.step('리포트 월 선택기는 한국어 연·월', async () => {
    await page.goto('/#/reports');
    await expect(page.getByTestId('report-month-year')).toContainText(`${today.slice(0, 4)}년`);
    await expect(page.getByTestId('report-month-month')).toContainText(`${Number(today.slice(5, 7))}월`);
    expect(ids.weeklyGoals.w).toBeTruthy();
  });
});
