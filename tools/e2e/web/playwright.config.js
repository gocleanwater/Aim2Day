// 화면 E2E 설정. 서버의 시험용 프록시(8081, review 컨테이너·aim2day_review DB)에 붙는다. 운영(80)에는 쓰지 않는다.
const { defineConfig } = require('@playwright/test');

module.exports = defineConfig({
  testDir: './tests',
  timeout: 60_000,
  expect: { timeout: 10_000 },
  fullyParallel: false,
  workers: 1,
  reporter: [['list']],
  globalTeardown: require.resolve('./teardown.js'),
  use: {
    baseURL: process.env.WEB_BASE_URL || 'http://192.168.136.128:8081',
    headless: true,
    locale: 'ko-KR',
    timezoneId: 'Asia/Seoul',
    trace: 'retain-on-failure',
  },
});
