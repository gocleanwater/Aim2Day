// F3: 대시보드 — 행동 추가(목표·그 밖의 할 일), 완료로 주간 진행률 변화, 사분면 배치·확정, 달력 요약 완료, 삭제, 날짜 이동
const { test, expect } = require('@playwright/test');
const { signupViaApi, sql, PASSWORD } = require('../helpers');

async function login(page, email) {
  await page.goto('/#/login');
  await page.getByTestId('login-email').fill(email);
  await page.getByTestId('login-password').fill(PASSWORD);
  await page.getByTestId('login-submit').click();
}

async function apiPost(page, url, data, method = 'post') {
  const xsrf = (await page.context().cookies()).find((c) => c.name === 'XSRF-TOKEN').value;
  const r = await page.request[method](url, { data, headers: { 'X-XSRF-TOKEN': xsrf } });
  if (!r.ok()) throw new Error(`${method} ${url} ${r.status()} ${await r.text()}`);
  return (await r.json()).data;
}

test('dashboard actions, progress, quadrant, calendar', async ({ page, request }) => {
  const email = await signupViaApi(request, 'd');
  const uid = sql(`SELECT id FROM users WHERE email='${email}'`);
  await login(page, email);
  await expect(page).toHaveURL(/#\/onboarding/);

  // 목표 트리는 API로 준비한다(온보딩 화면은 F2에서 시험).
  const today = (await (await page.request.get('/api/settings')).json()).data.today;
  const ids = await apiPost(page, '/api/goals/onboarding/complete', {
    yearlyGoals: [{ tempId: 'y', title: '백엔드' }],
    monthlyGoals: [{ tempId: 'm', yearlyGoalTempId: 'y', title: '알고리즘' }],
    weeklyGoals: [{ tempId: 'w', monthlyGoalTempId: 'm', title: '문제 풀기', measurementType: 'CHECKLIST' }],
    actions: [],
  });
  const weeklyId = ids.weeklyGoals.w;
  await apiPost(page, '/api/calendar/items', { itemType: 'TASK', title: '과제 제출', startDate: today });

  await page.goto('/#/dashboard');
  await expect(page.getByTestId('weekly-' + weeklyId)).toContainText('문제 풀기');
  await expect(page.getByTestId('weekly-' + weeklyId)).toContainText('기록 없음');

  await test.step('목표 행동 추가(주간 자동 연결)와 그 밖의 할 일 추가', async () => {
    // 행동 추가 후보(월간)는 선택한 날짜 기준으로 따로 읽으므로 선택지가 채워질 때까지 기다린다.
    await expect(page.getByTestId('add-action-destination').locator('option', { hasText: '알고리즘' })).toHaveCount(1);
    const goalOpts = await page.getByTestId('add-action-destination').locator('option').allTextContents();
    await page.getByTestId('add-action-destination').selectOption({ label: goalOpts.find((o) => o.includes('알고리즘')) });
    await page.getByTestId('add-action-title').fill('2문제 풀기');
    await page.getByTestId('add-action-submit').click();
    await expect(page.getByTestId('action-list')).toContainText('2문제 풀기');
    const opts = await page.getByTestId('add-action-destination').locator('option').allTextContents();
    await page.getByTestId('add-action-destination').selectOption({ label: opts.find((o) => o.includes('그 밖')) });
    await page.getByTestId('add-action-title').fill('장보기');
    await page.getByTestId('add-action-submit').click();
    await expect(page.getByTestId('action-list')).toContainText('장보기');
    expect(sql(`SELECT COUNT(*) FROM actions WHERE user_id=${uid} AND weekly_goal_id=${weeklyId}`)).toBe('1');
  });

  const goalActionId = sql(`SELECT id FROM actions WHERE user_id=${uid} AND weekly_goal_id=${weeklyId}`);
  const otherId = sql(`SELECT id FROM actions WHERE user_id=${uid} AND weekly_goal_id IS NULL`);

  await test.step('목표 행동 완료 → 주간 진행률 100%', async () => {
    await page.getByTestId('action-check-' + goalActionId).check();
    await expect(page.getByTestId('weekly-' + weeklyId)).toContainText('100');
    expect(sql(`SELECT is_completed FROM actions WHERE id=${goalActionId}`)).toBe('1');
  });

  await test.step('사분면: 트레이 점을 Q1에 놓고 오늘 분류 확정', async () => {
    await page.getByTestId('point-' + otherId).click();
    await page.getByTestId('quadrant-Q1').click();
    await expect(page.getByTestId('quad-confirm')).toBeEnabled();
    await page.getByTestId('quad-confirm').click();
    await expect.poll(() => sql(`SELECT COUNT(*) FROM action_quadrant_placements WHERE action_id=${otherId} AND importance_x IS NOT NULL`)).toBe('1');
  });

  await test.step('달력 요약의 해야 할 일 완료', async () => {
    const task = page.getByTestId('cal-tasks').locator('[data-testid^="cal-task-check-"]').first();
    await task.check();
    await expect.poll(() => sql(`SELECT is_completed FROM calendar_items WHERE user_id=${uid}`)).toBe('1');
  });

  await test.step('그 밖의 할 일 삭제(확인 모달)', async () => {
    await page.getByTestId('action-delete-' + otherId).click();
    await page.getByRole('button', { name: /삭제/ }).last().click();
    await expect(page.getByTestId('action-' + otherId)).toHaveCount(0);
  });

  await test.step('날짜 이동: 어제로 가면 오늘 행동이 안 보이고, 오늘로 돌아오면 보인다', async () => {
    await page.getByTestId('date-prev').click();
    await expect(page.getByTestId('action-' + goalActionId)).toHaveCount(0);
    await page.getByTestId('date-today').click();
    await expect(page.getByTestId('action-' + goalActionId)).toBeVisible();
  });
});
