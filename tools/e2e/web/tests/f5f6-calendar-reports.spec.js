// F5 달력: 일정 추가·걸친 표시·완료·수정·필터·삭제 / F6 리포트: 탭, 집계, 달성률 막대, 직접 회고 저장 / 모든 화면 콘솔 오류 없음
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

function addDays(iso, n) {
  const d = new Date(iso + 'T00:00:00Z');
  d.setUTCDate(d.getUTCDate() + n);
  return d.toISOString().slice(0, 10);
}

test('calendar and reports', async ({ page, request }) => {
  const email = await signupViaApi(request, 'c');
  const uid = sql(`SELECT id FROM users WHERE email='${email}'`);
  const errors = [];
  page.on('pageerror', (e) => errors.push(String(e)));
  page.on('console', (m) => { if (m.type() === 'error' && !/40[19]|422/.test(m.text())) errors.push(m.text()); });
  await login(page, email);
  await expect(page).toHaveURL(/#\/onboarding/);
  const today = (await (await page.request.get('/api/settings')).json()).data.today;
  const ids = await api(page, 'post', '/api/goals/onboarding/complete', {
    yearlyGoals: [{ tempId: 'y', title: '백엔드' }],
    monthlyGoals: [{ tempId: 'm', yearlyGoalTempId: 'y', title: '알고리즘' }],
    weeklyGoals: [{ tempId: 'w', monthlyGoalTempId: 'm', title: '문제 풀기' }],
    actions: [{ type: 'GOAL', weeklyGoalTempId: 'w', title: '2문제', performedDate: today }],
  });
  const yid = ids.yearlyGoals.y;

  await test.step('달력: 오늘 칸 선택 → 이틀짜리 일정 추가(목표 연결) → 두 칸에 표시', async () => {
    await page.goto('/#/calendar');
    await page.getByTestId('cal-day-' + today).click();
    await page.getByTestId('item-type-event').check();
    await page.getByTestId('item-title').fill('발표 준비');
    await page.getByTestId('item-start').fill(today);
    await page.getByTestId('item-end').fill(addDays(today, 1));
    const opts = await page.getByTestId('item-goal').locator('option').allTextContents();
    await page.getByTestId('item-goal').selectOption({ label: opts.find((o) => o.includes('백엔드')) });
    await page.getByTestId('item-submit').click();
    await expect(page.getByTestId('day-items')).toContainText('발표 준비');
    const itemId = sql(`SELECT id FROM calendar_items WHERE user_id=${uid}`);
    expect(sql(`SELECT CONCAT(item_type,':',DATEDIFF(end_date,start_date),':',yearly_goal_id) FROM calendar_items WHERE id=${itemId}`)).toBe(`EVENT:1:${yid}`);
    // 다음 날이 같은 달 격자 안에 있으면 그 칸에도 칩이 있어야 한다.
    const next = page.getByTestId('cal-day-' + addDays(today, 1));
    if (await next.count()) await expect(next.locator(`[data-item-id="${itemId}"]`)).toHaveCount(1);
  });
  const itemId = sql(`SELECT id FROM calendar_items WHERE user_id=${uid}`);

  await test.step('달력: 완료·수정·목표 필터·삭제', async () => {
    await page.getByTestId('item-check-' + itemId).check();
    await expect.poll(() => sql(`SELECT is_completed FROM calendar_items WHERE id=${itemId}`)).toBe('1');
    await page.getByTestId('item-edit-' + itemId).click();
    await page.getByTestId('item-edit-title').fill('발표 리허설');
    await page.getByTestId('item-edit-save').click();
    await expect(page.getByTestId('day-items')).toContainText('발표 리허설');
    await page.getByTestId('filter-goal-' + yid).uncheck();
    await expect(page.getByTestId('day-items')).not.toContainText('발표 리허설');
    await page.getByTestId('filter-goal-' + yid).check();
    await expect(page.getByTestId('day-items')).toContainText('발표 리허설');
    await page.getByTestId('item-delete-' + itemId).click();
    await page.getByRole('dialog').getByRole('button', { name: /삭제/ }).click();
    await expect.poll(() => sql(`SELECT COUNT(*) FROM calendar_items WHERE id=${itemId}`)).toBe('0');
  });

  await test.step('리포트: 이번 달 월간 탭, 완료 1건 집계, 결과물 없음, 미확정 목표 note, 회고 저장', async () => {
    const actionId = sql(`SELECT id FROM actions WHERE user_id=${uid}`);
    await api(page, 'put', `/api/actions/${actionId}/completion`, { completed: true });
    await page.goto('/#/reports');
    await expect(page.getByTestId('report-tab-MONTHLY')).toBeVisible();
    await expect(page.getByTestId('report-action-count')).toContainText('1');
    await expect(page.getByTestId('report-active-days')).toContainText('1');
    await expect(page.getByTestId('report-actions')).toContainText('2문제');
    await expect(page.getByTestId('report-results')).toContainText('기록된 결과물 없음');
    await expect(page.getByTestId('report-goal-' + yid)).toContainText('달성 기준 설정 전');
    await page.getByTestId('review-improvements').fill('하루 2문제 꾸준히');
    await page.getByTestId('review-next-focus').fill('그래프');
    await page.getByTestId('review-save').click();
    await expect.poll(() => sql(`SELECT CONCAT(improvements,'|',next_focus) FROM period_reviews WHERE user_id=${uid}`)).toBe('하루 2문제 꾸준히|그래프');
    await page.getByTestId('review-next-focus').fill('트리');
    await page.getByTestId('review-save').click();
    await expect.poll(() => sql(`SELECT next_focus FROM period_reviews WHERE user_id=${uid}`)).toBe('트리');
    await page.reload();
    await expect(page.getByTestId('review-next-focus')).toHaveValue('트리');
  });

  await test.step('모든 화면 열기: 콘솔 오류 없음', async () => {
    for (const h of ['#/dashboard', '#/calendar', '#/goals', `#/goals/${yid}`, '#/plan', '#/goals/trash', '#/reports', '#/settings']) {
      await page.goto('/' + h);
      await page.waitForLoadState('networkidle');
    }
    expect(errors).toEqual([]);
  });
});
