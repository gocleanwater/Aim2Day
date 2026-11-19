// 화면 E2E 공용 도구: 서버 명령 실행, 인증번호 읽기, 시험 계정 만들기·기록.
const { execFileSync } = require('child_process');
const fs = require('fs');
const path = require('path');

const HOST = 'gocleanwater@192.168.136.128';
const CREATED = path.join(__dirname, '.created-emails');
const PASSWORD = 'Aim2day!2026';

function ssh(cmd) {
  return execFileSync('plink', ['-batch', '-pw', '2323', HOST, cmd], { encoding: 'utf8' });
}

function sql(q) {
  return ssh(`docker exec aim2day-mysql mysql --default-character-set=utf8mb4 -uaim2day_app -p'aim2daypass!' aim2day_review -N -e "${q}" 2>/dev/null`).trim();
}

let seq = 0;
function newEmail(tag = 'w') {
  const email = `${tag}${Date.now()}${String(seq++).padStart(3, '0')}@test.aim2day.dev`;
  fs.appendFileSync(CREATED, email + '\n'); // 실패해도 teardown이 지울 수 있게 먼저 기록한다
  return email;
}

// 메일 대신 로그에 찍힌 인증번호 또는 재설정 링크(받는 주소는 마스킹)를 읽는다. purpose: signup-code | password-reset
async function mailCode(email, purpose = 'signup-code') {
  const masked = email.replace(/^(.).*@/, '$1***@');
  for (let i = 0; i < 30; i++) {
    const logs = ssh(`docker logs aim2day-user-service-review 2>&1 | grep -F "[MAIL] to=${masked} purpose=${purpose}" | tail -1`);
    const m = logs.match(/code=(\d{6})/) || logs.match(/link=(\S+)/);
    if (m) return m[1];
    await new Promise((r) => setTimeout(r, 500));
  }
  throw new Error('mail code not found for ' + masked);
}

// API로 빠르게 가입만 해 둔다(화면 흐름 자체를 시험하지 않는 테스트용).
async function signupViaApi(request, tag = 'w') {
  const email = newEmail(tag);
  await request.get('/api/auth/csrf');
  const xsrf = async () => (await request.storageState()).cookies.find((c) => c.name === 'XSRF-TOKEN').value;
  const post = async (url, data) => request.post(url, { data, headers: { 'X-XSRF-TOKEN': await xsrf() } });
  let r = await post('/api/auth/email-codes', { email });
  if (!r.ok()) throw new Error('email-codes ' + r.status());
  const code = await mailCode(email);
  r = await post('/api/auth/email-codes/verify', { email, code });
  const token = (await r.json()).data.signupToken;
  r = await post('/api/auth/signup', { signupToken: token, username: 'e2e', password: PASSWORD, passwordConfirm: PASSWORD });
  if (r.status() !== 201) throw new Error('signup ' + r.status());
  return email;
}

module.exports = { ssh, sql, newEmail, mailCode, signupViaApi, PASSWORD, CREATED };
