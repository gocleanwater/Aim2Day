// 날짜 문자열(YYYY-MM-DD) 계산. 서버가 "오늘"을 계정 시간대로 정하므로(API §2.3) 브라우저 시계·시간대를 쓰지 않고,
// 받은 날짜 문자열에 일 수만 더하고 뺀다. UTC로 계산해야 서머타임·로컬 시간대 때문에 하루가 밀리지 않는다.

export function isValidIsoDate(text) {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(text)) return false;
  const [y, m, d] = text.split("-").map(Number);
  const date = new Date(Date.UTC(y, m - 1, d));
  return date.getUTCFullYear() === y && date.getUTCMonth() === m - 1 && date.getUTCDate() === d;
}

export function addDays(iso, days) {
  const [y, m, d] = iso.split("-").map(Number);
  return new Date(Date.UTC(y, m - 1, d + days)).toISOString().slice(0, 10);
}
