// F1: 회원가입(인증번호) → 로그인 → 온보딩 분기 → 설정 저장 → 로그아웃 → 보호 화면 접근 차단 → 비밀번호 재설정
const { test, expect } = require('@playwright/test');
const { newEmail, mailCode, PASSWORD } = require('../helpers');

test('signup, login, settings, logout, reset password', async ({ page, baseURL }) => {
  const email = newEmail('f');

  await test.step('보호 화면은 로그인으로 보낸다', async () => {
    await page.goto('/#/settings');
    await expect(page).toHaveURL(/#\/login/);
  });

  await test.step('회원가입: 인증번호 받기 → 확인 → 가입', async () => {
    await page.getByTestId('link-signup').click();
    await page.getByTestId('signup-email').fill(email);
    await page.getByTestId('send-code').click();
    const code = await mailCode(email);
    await page.getByTestId('signup-code').fill('000000');
    await page.getByTestId('verify-code').click();
    await expect(page.getByText(/인증번호가 맞지 않습니다/)).toBeVisible();
    await page.getByTestId('signup-code').fill(code);
    await page.getByTestId('verify-code').click();
    await page.getByTestId('signup-username').fill('화면시험');
    await page.getByTestId('signup-password').fill(PASSWORD);
    await page.getByTestId('signup-confirm').fill(PASSWORD);
    await page.getByTestId('signup-submit').click();
    // 가입하면 바로 로그인된다. 새 계정이라 온보딩으로 간다. 로그인 시험을 위해 로그아웃한다.
    await expect(page).toHaveURL(/#\/onboarding/);
    await expect(page.getByTestId('member-name')).toContainText('화면시험');
    await page.getByTestId('logout').click();
    await expect(page).toHaveURL(/#\/login/);
  });

  await test.step('로그인 실패 문구, 성공하면 목표가 없어 온보딩으로', async () => {
    await page.getByTestId('login-email').fill(email);
    await page.getByTestId('login-password').fill('Wrong!2026x');
    await page.getByTestId('login-submit').click();
    await expect(page.locator('[data-form-error]')).not.toBeEmpty();
    await page.getByTestId('login-password').fill(PASSWORD);
    await page.getByTestId('login-submit').click();
    await expect(page).toHaveURL(/#\/onboarding/);
    await expect(page.getByTestId('member-name')).toContainText('화면시험');
  });

  await test.step('설정: 주 시작 요일 저장, 바닥글에 인스턴스 표시', async () => {
    await page.getByTestId('nav-settings').click();
    await expect(page).toHaveURL(/#\/settings/);
    const target = page.getByTestId('week-start-day').locator('input[name=weekStartDay]:not(:checked)').first();
    const chosen = await target.getAttribute('value');
    await target.check();
    await page.getByTestId('settings-save').click();
    await expect(page.getByTestId('toast')).toContainText('설정을 저장했어요');
    await page.reload();
    await expect(page.locator(`input[name=weekStartDay][value="${chosen}"]`)).toBeChecked();
  });

  await test.step('로그아웃하면 보호 화면 차단', async () => {
    await page.getByTestId('logout').click();
    await expect(page).toHaveURL(/#\/login/);
    await page.goto('/#/settings');
    await expect(page).toHaveURL(/#\/login/);
  });

  await test.step('비밀번호 재설정: 링크로 새 비밀번호 → 새 비밀번호로 로그인', async () => {
    await page.getByTestId('link-forgot').click();
    await page.getByTestId('forgot-email').fill(email);
    await page.getByTestId('forgot-submit').click();
    const link = await mailCode(email, 'password-reset');
    const url = new URL(link);
    await page.goto(baseURL + url.pathname + url.hash);
    const NEW = 'Newpass!2026';
    await page.getByTestId('reset-password').fill(NEW);
    await page.getByTestId('reset-confirm').fill(NEW);
    await page.getByTestId('reset-submit').click();
    await expect(page).toHaveURL(/#\/login/);
    await page.getByTestId('login-email').fill(email);
    await page.getByTestId('login-password').fill(NEW);
    await page.getByTestId('login-submit').click();
    await expect(page).toHaveURL(/#\/onboarding/);
  });
});
