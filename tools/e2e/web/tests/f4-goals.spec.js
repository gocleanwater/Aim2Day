// F4: 연간 목표 생성 → 달성 기준 작성·확정(기준선)·달성 → 월·주 계획(수치형 + 수치 기록) → 삭제(확인 모달) → 휴지통 복원
const { test, expect } = require('@playwright/test');
const { signupViaApi, sql, PASSWORD } = require('../helpers');

async function login(page, email) {
  await page.goto('/#/login');
  await page.getByTestId('login-email').fill(email);
  await page.getByTestId('login-password').fill(PASSWORD);
  await page.getByTestId('login-submit').click();
}

test('yearly goal, criteria, plan, numeric record, delete and restore', async ({ page, request }) => {
  const email = await signupViaApi(request, 'g');
  const uid = sql(`SELECT id FROM users WHERE email='${email}'`);
  await login(page, email);
  await expect(page).toHaveURL(/#\/onboarding/);

  await test.step('연간 목표 생성', async () => {
    await page.goto('/#/goals');
    await page.getByTestId('new-goal-title').fill('영어 회화');
    await page.getByTestId('new-goal-submit').click();
    await expect(page.getByTestId('goal-cards')).toContainText('영어 회화');
  });
  const yid = sql(`SELECT id FROM yearly_goals WHERE user_id=${uid}`);

  await test.step('달성 기준: 40 + 60 작성 → 확정(40 기준선) → 60 달성 = 100%', async () => {
    await page.goto(`/#/goals/${yid}`);
    await page.getByTestId('criterion-text-0').fill('기초 회화');
    await page.getByTestId('criterion-weight-0').fill('40');
    await page.getByTestId('criteria-add').click();
    await page.getByTestId('criterion-text-1').fill('원어민과 30분 대화');
    await page.getByTestId('criterion-weight-1').fill('60');
    await expect(page.getByTestId('criteria-total')).toContainText('100');
    await page.getByTestId('criteria-confirm').click();
    const ids = sql(`SELECT GROUP_CONCAT(id ORDER BY id) FROM yearly_goal_criteria WHERE yearly_goal_id=${yid}`).split(',');
    await page.getByTestId('baseline-' + ids[0]).check();
    await page.getByTestId('confirm-submit').click();
    await expect(page.getByTestId('criteria-list')).toBeVisible();
    expect(sql(`SELECT initial_baseline_rate FROM yearly_goals WHERE id=${yid}`)).toBe('40');
    await page.getByTestId('achieve-' + ids[1]).check();
    await expect.poll(() => sql(`SELECT COUNT(*) FROM yearly_goal_criteria WHERE yearly_goal_id=${yid} AND is_achieved=1`)).toBe('2');
  });

  await test.step('월·주 계획: 월간 → 수치형 주간(10) → 수치 3 기록', async () => {
    await page.goto('/#/plan');
    await page.getByTestId('monthly-title').fill('발음 연습');
    await page.getByTestId('monthly-submit').click();
    await expect(page.getByTestId('goal-list')).toContainText('발음 연습');
    await page.getByTestId('weekly-title').fill('쉐도잉 10회');
    await page.getByTestId('weekly-type-numeric').check();
    await page.getByTestId('weekly-target').fill('10');
    await page.getByTestId('weekly-submit').click();
    await expect(page.getByTestId('goal-list')).toContainText('쉐도잉 10회');
    const wid = sql(`SELECT w.id FROM weekly_goals w JOIN monthly_goals m ON m.id=w.monthly_goal_id WHERE m.yearly_goal_id=${yid}`);
    await page.getByTestId('records-toggle-' + wid).click();
    await page.getByTestId('record-amount-' + wid).fill('3');
    await page.getByTestId('record-add-' + wid).click();
    await expect.poll(() => sql(`SELECT ROUND(current_value) FROM weekly_goals WHERE id=${wid}`)).toBe('3');
  });

  await test.step('연간 삭제: 하위가 있어 확인 모달 → 삭제 → 휴지통 → 복원', async () => {
    await page.goto(`/#/goals/${yid}`);
    await page.getByTestId('goal-delete').click();
    await expect(page.getByRole('dialog')).toContainText(/1/);
    await page.getByRole('dialog').getByRole('button', { name: /삭제/ }).click();
    await expect(page).toHaveURL(/#\/goals$/);
    expect(sql(`SELECT deleted_at IS NOT NULL FROM yearly_goals WHERE id=${yid}`)).toBe('1');
    await page.getByTestId('trash-link').click();
    await expect(page.getByTestId(`trash-yearly-${yid}`)).toContainText('영어 회화');
    await page.getByTestId(`restore-yearly-${yid}`).click();
    await expect(page.getByTestId(`trash-yearly-${yid}`)).toHaveCount(0);
    expect(sql(`SELECT deleted_at IS NULL FROM yearly_goals WHERE id=${yid}`)).toBe('1');
  });
});
