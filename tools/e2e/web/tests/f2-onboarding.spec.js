// F2: 온보딩 네 질문 → 단계마다 임시 저장 → 새로고침해도 이어하기 → 한 번에 완료 → 대시보드, DB에 목표·행동 생성
const { test, expect } = require('@playwright/test');
const { signupViaApi, sql, PASSWORD } = require('../helpers');

async function login(page, email) {
  await page.goto('/#/login');
  await page.getByTestId('login-email').fill(email);
  await page.getByTestId('login-password').fill(PASSWORD);
  await page.getByTestId('login-submit').click();
}

test('onboarding four steps with draft resume and complete', async ({ page, request }) => {
  const email = await signupViaApi(request, 'o');
  const uid = sql(`SELECT id FROM users WHERE email='${email}'`);
  await login(page, email);
  await expect(page).toHaveURL(/#\/onboarding/);

  await test.step('1단계: 올해 목표 입력 → 다음(임시 저장)', async () => {
    await expect(page.getByTestId('onboarding-question')).toContainText('집중하고 싶나요');
    await page.getByTestId('yearly-title-0').fill('백엔드 개발자 취업');
    await page.getByTestId('onboarding-next').click();
    await expect(page.getByTestId('monthly-title-0')).toBeVisible();
    expect(sql(`SELECT current_step FROM onboarding_drafts WHERE user_id=${uid}`)).toBe('2');
  });

  await test.step('새로고침해도 2단계로 이어하기', async () => {
    await page.reload();
    await expect(page.getByTestId('monthly-title-0')).toBeVisible();
  });

  await test.step('2단계: 이번 달 목표', async () => {
    await page.getByTestId('monthly-title-0').fill('알고리즘 기초');
    await page.getByTestId('onboarding-next').click();
  });

  await test.step('3단계: 이번 주 목표(수치형 10)', async () => {
    await page.getByTestId('weekly-title-0').fill('문제 10개 풀기');
    await page.getByTestId('weekly-type-numeric-0').check();
    await page.getByTestId('weekly-target-0').fill('10');
    await page.getByTestId('onboarding-next').click();
  });

  await test.step('4단계: 오늘 행동 → 계획 완료 → 대시보드', async () => {
    await page.getByTestId('action-type-goal-0').check();
    await page.getByTestId('action-title-0').fill('문제 2개 풀기');
    const contribution = page.getByTestId('action-contribution-0');
    if (await contribution.isVisible()) await contribution.fill('2');
    await page.getByTestId('onboarding-complete').click();
    await expect(page).toHaveURL(/#\/dashboard/);
  });

  await test.step('DB: 연간·월간·주간·행동 생성, 임시 저장 삭제', async () => {
    expect(sql(`SELECT COUNT(*) FROM yearly_goals WHERE user_id=${uid}`)).toBe('1');
    expect(sql(`SELECT COUNT(*) FROM monthly_goals m JOIN yearly_goals y ON y.id=m.yearly_goal_id WHERE y.user_id=${uid}`)).toBe('1');
    expect(sql(`SELECT CONCAT(w.measurement_type,':',ROUND(w.target_value)) FROM weekly_goals w JOIN monthly_goals m ON m.id=w.monthly_goal_id JOIN yearly_goals y ON y.id=m.yearly_goal_id WHERE y.user_id=${uid}`)).toBe('NUMERIC:10');
    expect(sql(`SELECT COUNT(*) FROM actions WHERE user_id=${uid} AND weekly_goal_id IS NOT NULL`)).toBe('1');
    expect(sql(`SELECT COUNT(*) FROM onboarding_drafts WHERE user_id=${uid}`)).toBe('0');
  });

  await test.step('온보딩이 끝나면 #/onboarding 진입 시 대시보드로', async () => {
    await page.goto('/#/onboarding');
    await expect(page).toHaveURL(/#\/dashboard/);
  });
});

test('onboarding: input caps title at 100, server 400 on resumed draft maps to step 1 field', async ({ page, request }) => {
  const email = await signupViaApi(request, 'q');
  await login(page, email);
  await expect(page).toHaveURL(/#\/onboarding/);
  await page.getByTestId('yearly-title-0').fill('가'.repeat(101));
  await expect(page.getByTestId('yearly-title-0')).toHaveValue('가'.repeat(100));

  // 화면을 거치지 않은 101자 제목을 임시 저장에 넣어 두고(다른 기기·옛 버전에서 저장된 경우), 이어하기 → 완료하면 서버 400이 1단계 입력칸에 붙어야 한다.
  const xsrf = (await page.context().cookies()).find((c) => c.name === 'XSRF-TOKEN').value;
  const today = (await (await page.request.get('/api/settings')).json()).data.today;
  const draftData = {
    seq: 4,
    yearlyGoals: [{ tempId: 'y1', title: '가'.repeat(101) }],
    monthlyGoals: [{ tempId: 'm2', yearlyGoalTempId: 'y1', title: '월간', description: '' }],
    weeklyGoals: [{ tempId: 'w3', monthlyGoalTempId: 'm2', title: '주간', measurementType: 'CHECKLIST', targetValue: null }],
    actions: [{ type: 'GOAL', weeklyGoalTempId: 'w3', title: '행동', performedDate: today }],
  };
  const r = await page.request.put('/api/goals/onboarding/draft', { data: { currentStep: 4, draftData }, headers: { 'X-XSRF-TOKEN': xsrf } });
  expect([200, 201]).toContain(r.status());
  await page.reload();
  // 화면에서 입력한 101자(100자로 잘림)가 브라우저 사본으로 남아 있어, 다른 곳에서 저장된 draft와 고르는 안내가 뜬다. 서버 쪽을 고른다.
  await page.getByTestId('conflict-load-server').click();
  await expect(page.getByTestId('onboarding-complete')).toBeVisible();
  await page.getByTestId('onboarding-complete').click();
  await expect(page.locator('[data-error-for="yearlyGoals[0].title"]')).not.toBeEmpty();
  await expect(page.getByTestId('yearly-title-0')).toBeVisible();
  await expect(page).toHaveURL(/#\/onboarding/);
});
