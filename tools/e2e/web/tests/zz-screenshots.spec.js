// 화면 캡처(시각 점검용). SHOTS=1일 때만 돈다. 결과는 tools/e2e/web/screenshots/(커밋하지 않음).
const { test, expect } = require('@playwright/test');
const { signupViaApi, PASSWORD } = require('../helpers');

test.skip(!process.env.SHOTS, 'SHOTS=1일 때만');

test('screenshots', async ({ page, request }) => {
  await page.setViewportSize({ width: 1440, height: 1000 });
  const email = await signupViaApi(request, 's');
  await page.goto('/#/login');
  await page.screenshot({ path: 'screenshots/01-login.png', fullPage: true });
  await page.getByTestId('login-email').fill(email);
  await page.getByTestId('login-password').fill(PASSWORD);
  await page.getByTestId('login-submit').click();
  await expect(page).toHaveURL(/#\/onboarding/);
  await page.screenshot({ path: 'screenshots/02-onboarding.png', fullPage: true });
  const xsrf = (await page.context().cookies()).find((c) => c.name === 'XSRF-TOKEN').value;
  const today = (await (await page.request.get('/api/settings')).json()).data.today;
  await page.request.post('/api/goals/onboarding/complete', { headers: { 'X-XSRF-TOKEN': xsrf }, data: {
    yearlyGoals: [{ tempId: 'y', title: '백엔드 개발자 취업' }, { tempId: 'z', title: '영어 회화' }],
    monthlyGoals: [{ tempId: 'm', yearlyGoalTempId: 'y', title: '알고리즘 기초' }],
    weeklyGoals: [{ tempId: 'w', monthlyGoalTempId: 'm', title: '문제 10개 풀기', measurementType: 'NUMERIC', targetValue: 10 }],
    actions: [{ type: 'GOAL', weeklyGoalTempId: 'w', title: '문제 2개 풀기', performedDate: today, contributionValue: 2 },
              { type: 'MAINTENANCE', title: '장보기', performedDate: today }],
  } });
  await page.request.post('/api/calendar/items', { headers: { 'X-XSRF-TOKEN': xsrf }, data: { itemType: 'TASK', title: '과제 제출', startDate: today } });
  for (const [h, name, ready] of [['#/dashboard', '03-dashboard', 'action-list'], ['#/calendar', '04-calendar', 'cal-month-label'], ['#/goals', '05-goals', 'goal-cards'], ['#/plan', '06-plan', 'goal-list'], ['#/reports', '07-reports', 'report-action-count'], ['#/settings', '08-settings', 'settings-save']]) {
    await page.goto('/' + h);
    await expect(page.getByTestId(ready)).toBeVisible();
    await page.waitForTimeout(500);
    await page.screenshot({ path: `screenshots/${name}.png`, fullPage: true });
  }
});
