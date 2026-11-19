// 이번 실행에서 만든 시험 계정을 시험용 DB에서 지운다.
const fs = require('fs');
const { ssh, CREATED } = require('./helpers');

module.exports = async () => {
  if (!fs.existsSync(CREATED)) return;
  const emails = fs.readFileSync(CREATED, 'utf8').split('\n').filter(Boolean);
  if (emails.length) {
    // 서버 정리가 실패하면(ssh 예외) 목록 파일을 남겨 다음 실행에서 다시 지운다.
    console.log(ssh(`cd /tmp/e2e && python3 cleanup_users.py ${emails.join(' ')}`).trim());
  }
  fs.unlinkSync(CREATED);
};
