# Aim2Day API 명세서

## 1. 개요

이 문서는 [요구사항 명세서](01_requirements.md), [화면 흐름과 와이어프레임](02_user-flow-and-wireframes.md), [기능 명세서](03_functional-specification.md), [ERD 명세서](04_entity-relationship-diagram.md)를 바탕으로 제출판 REST API를 정의한다. AI·BYOK·결제·플러그인/MCP는 제출판에서 API를 제공하지 않는다. 프론트엔드가 해당 진입점에 `해당 기능은 현재 개발 중입니다.`를 직접 표시한다.

### 1.1 서비스와 경로

모든 요청은 Nginx 역방향 프록시를 거친다. 경로 접두사로 서비스를 나누고, `goal-service`는 3개 인스턴스에 RR로 분산한다.

| 접두사 | 서비스 | 인스턴스 | 담당 |
|---|---|---|---|
| `/api/auth/**` | `user-service` | 1 | 가입, 이메일 인증, 로그인·로그아웃, 비밀번호 재설정 |
| `/api/users/**` | `user-service` | 1 | 내 기본 정보 |
| `/api/settings/**` | `goal-service` | 3 (RR) | 주 시작 요일, 시간대 (ERD 결정 14) |
| `/api/goals/**` | `goal-service` | 3 (RR) | 온보딩, 연·월·주 목표, 달성 기준, 수치 기록, 휴지통, 진행 기록 |
| `/api/actions/**` | `goal-service` | 3 (RR) | 오늘 행동, 사분면 분류 |
| `/api/calendar/**` | `goal-service` | 3 (RR) | 달력 항목, 대시보드 `해야 할 일`·`일정` |
| `/api/reports/**` | `goal-service` | 3 (RR) | 기간 리포트, 직접 회고 |

### 1.2 엔드포인트 목록

| 메서드 | 경로 | 설명 | 기능 ID |
|---|---|---|---|
| GET | `/api/auth/csrf` | CSRF 토큰 쿠키 발급(인증 불필요, 결정 13) | NFR-009 |
| POST | `/api/auth/signup` | 회원가입 | FN-AUTH-01 |
| POST | `/api/auth/verification-emails` | 인증 메일 재발송 | FN-AUTH-01, 03 |
| POST | `/api/auth/email-verification` | 이메일 인증 완료 | FN-AUTH-02 |
| POST | `/api/auth/login` | 로그인 | FN-AUTH-03 |
| POST | `/api/auth/logout` | 로그아웃 | FN-AUTH-03 |
| POST | `/api/auth/password-reset-requests` | 비밀번호 재설정 링크 요청 | FN-AUTH-04 |
| POST | `/api/auth/password-reset` | 새 비밀번호 제출 | FN-AUTH-04 |
| GET | `/api/users/me` | 내 기본 정보 | AUTH-005, 009 |
| GET | `/api/settings` | 설정과 오늘·이번 주·이번 달 | FN-SET-01, 02 |
| PUT | `/api/settings` | 설정 변경 | FN-SET-01, 02 |
| GET | `/api/goals/onboarding/status` | 온보딩 필요 여부 | FN-PLAN-01 |
| GET·PUT·DELETE | `/api/goals/onboarding/draft` | 온보딩 임시 저장 | 수용기준 21 |
| POST | `/api/goals/onboarding/complete` | 온보딩 일괄 저장 | FN-PLAN-01 |
| GET | `/api/goals/yearly` | 연간 목표 목록 | FN-GOAL-01 |
| POST | `/api/goals/yearly` | 연간 목표 생성 | FN-GOAL-01 |
| PUT | `/api/goals/yearly/order` | 카드 순서 저장 | FN-GOAL-01 |
| GET | `/api/goals/yearly/{id}` | 연간 목표 상세(하위 트리 포함) | DASH-009 |
| PATCH | `/api/goals/yearly/{id}` | 연간 목표 수정·상태 변경 | FN-GOAL-02 |
| DELETE | `/api/goals/yearly/{id}` | 연간 목표 소프트 삭제 | FN-GOAL-03 |
| GET | `/api/goals/yearly/{id}/criteria` | 달성 기준 조회 | FN-CRIT-01 |
| PUT | `/api/goals/yearly/{id}/criteria` | 기준 초안 저장·확정 후 개정 | FN-CRIT-01 |
| POST | `/api/goals/yearly/{id}/criteria/confirm` | 기준 확정·최초 기준선 저장 | FN-CRIT-01 |
| PUT | `/api/goals/yearly/{id}/criteria/{criterionId}/achievement` | 기준 달성·달성 취소 | FN-CRIT-02 |
| GET | `/api/goals/monthly` | 월간 목표 목록 | FN-PLAN-02 |
| POST | `/api/goals/monthly` | 월간 목표 생성(현재 달) | FN-PLAN-02 |
| GET·PATCH·DELETE | `/api/goals/monthly/{id}` | 월간 목표 상세·수정·삭제 | FN-GOAL-02, 03 |
| GET | `/api/goals/weekly` | 주간 목표 목록 | FN-PLAN-03 |
| POST | `/api/goals/weekly` | 주간 목표 생성(현재 주) | FN-PLAN-03 |
| GET | `/api/goals/weekly/previous-summary` | 직전 주 실제 진행 | FN-PLAN-03 |
| GET·PATCH·DELETE | `/api/goals/weekly/{id}` | 주간 목표 상세·수정·삭제 | FN-GOAL-02, 03 |
| GET·POST | `/api/goals/weekly/{id}/numeric-records` | 수치 기록 조회·직접 입력 | FN-PROG-01 |
| DELETE | `/api/goals/weekly/{id}/numeric-records/{recordId}` | 직접 입력 기록 삭제 | FN-PROG-01 |
| GET | `/api/goals/trash` | 삭제된 목표 목록 | FN-GOAL-04, 05 |
| POST | `/api/goals/{level}/{id}/restore` | 목표 복원 | FN-GOAL-04 |
| GET | `/api/goals/progress-logs` | 최근 진행 기록 | DASH-005, PROG-007 |
| GET | `/api/actions` | 날짜별 행동 목록과 정렬, 지난 미완료 | FN-ACT-03, 04 |
| POST | `/api/actions` | 행동 추가 | FN-ACT-01 |
| PATCH·DELETE | `/api/actions/{id}` | 행동 수정·삭제 | FN-ACT-01 |
| PUT | `/api/actions/{id}/completion` | 행동 완료·완료 취소 | FN-ACT-02 |
| PUT | `/api/actions/{id}/placement` | 사분면에 점 놓기·옮기기·트레이로 되돌리기(미확정, 결정 19) | FN-QUAD-01 |
| DELETE | `/api/actions/{id}/placement/pending` | 확정하지 않은 변경 취소 | FN-QUAD-02 |
| POST | `/api/actions/placements/confirm` | 오늘 분류 확정 | FN-QUAD-02 |
| GET | `/api/calendar/items` | 기간별 달력 항목 | FN-CAL-01 |
| POST | `/api/calendar/items` | 달력 항목 생성 | FN-CAL-02 |
| PATCH·DELETE | `/api/calendar/items/{id}` | 달력 항목 수정·영구 삭제 | FN-CAL-02 |
| PUT | `/api/calendar/items/{id}/completion` | 달력 항목 완료·완료 취소 | FN-CAL-02 |
| GET | `/api/calendar/dashboard` | 대시보드 `해야 할 일`·`일정` | FN-DASH-01 |
| GET | `/api/reports/tabs` | 기준 월에 끝나는 리포트 탭 목록 | FN-REPORT-04 |
| GET | `/api/reports` | 기간 리포트 | FN-REPORT-01, 02, 04 |
| PUT | `/api/reports/reviews/{periodType}/{period}` | 직접 회고 저장 | FN-REPORT-03 |

## 2. 공통 규칙

### 2.1 요청·응답 형식

- 요청과 응답 본문은 `application/json; charset=UTF-8`이다. 필드 이름은 camelCase를 쓴다.
- 본문이 있는 변경 요청은 `Content-Type: application/json`이어야 한다.
- 성공 응답은 HTTP 200(조회·변경·삭제) 또는 201(생성)이며 다음 형식을 쓴다. 성공 `code`는 항상 `SUCCESS`다.

```json
{
  "code": "SUCCESS",
  "message": "요청이 처리되었습니다.",
  "data": { "id": 12, "title": "백엔드 개발자 취업 준비" }
}
```

- 오류 응답은 4xx·5xx이며 `data` 대신 `details`를 쓴다. `details`는 필요할 때만 넣고, 없으면 `null`이다. (`NFR-011`)

```json
{
  "code": "GOAL_LIMIT_EXCEEDED",
  "message": "2026년 목표는 최대 3개까지 만들 수 있습니다. 기존 목표를 삭제한 뒤 다시 시도해 주세요.",
  "details": { "year": 2026, "current": 3, "max": 3 }
}
```

- `message`는 화면에 그대로 보여줄 수 있는 한국어 문장이다. 프론트엔드는 분기 처리에 `message`가 아니라 `code`를 쓴다.
- 이 문서의 응답 예시는 `data` 부분만 적는다.

### 2.2 인증과 세션

- 로그인에 성공하면 `user-service`가 Spring Session으로 Redis 세션을 만들고 세션 쿠키 `SESSION`을 내려준다. 세션에는 `userId`를 저장한다. (`AUTH-003`, `NFR-004`)
- 세션 저장소는 사용자별 조회가 되는 인덱스형 저장소(`@EnableRedisIndexedHttpSession`, `RedisIndexedSessionRepository`)를 쓴다. 로그인할 때 `userId`를 문자열로 세션 속성 `FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME`에도 저장한다. 비밀번호 재설정 때 그 사용자의 모든 세션을 찾아 지우기 위해서다. `goal-service` 세 인스턴스도 같은 설정을 써서 같은 Redis 키 이름 공간의 세션을 읽는다. (결정 14)
- 인덱스형 저장소는 세션 만료 이벤트를 받으려고 Redis 키 공간 알림(`notify-keyspace-events`)이 필요하다. Spring Session이 기본으로 자동 설정하고, 이 프로젝트의 Redis는 직접 띄우는 컨테이너라 자동 설정이 그대로 동작한다. 관리형 Redis를 쓰게 되면 자동 설정이 막혀 있을 수 있으므로 직접 켜야 한다.
- 쿠키 속성은 `HttpOnly`, `SameSite=Lax`, `Path=/`다. HTTPS로 배포하면 `Secure`도 붙인다. (`NFR-009`)
- 세션은 마지막 요청 후 30분 동안 쓰지 않으면 만료된다.
- `goal-service`의 세 인스턴스는 같은 Redis에서 세션을 읽어 `userId`를 얻는다. 인스턴스가 바뀌어도 다시 로그인하지 않는다. (`DIST-004`, `DIST-006`)
- 인증이 필요 없는 API는 `/api/auth/**`뿐이다. 그 밖의 API는 유효한 세션이 없으면 401 `UNAUTHENTICATED`를 반환한다.
- 프론트엔드와 API는 프록시를 통해 같은 출처로 제공한다. 따라서 CORS를 열지 않는다.
- 교차 사이트 요청 위조(CSRF)는 Spring Security의 CSRF 보호로 막는다. `user-service`와 `goal-service` 모두에서 상태를 바꾸는 모든 요청(POST·PUT·PATCH·DELETE)에 적용하고, `/api/auth`의 로그인·가입 등 POST도 예외가 아니다. (결정 13)
  - 토큰 저장소는 `CookieCsrfTokenRepository.withHttpOnlyFalse()`다. 서버가 JS가 읽을 수 있는 쿠키 `XSRF-TOKEN`(`Path=/`)을 내려주고, 프론트엔드는 그 값을 요청 헤더 `X-XSRF-TOKEN`에 넣어 보낸다. 서버는 쿠키 값과 헤더 값을 비교한다. 조회(GET)에는 요구하지 않는다.
  - 토큰을 서버에 저장하지 않으므로 `goal-service` 어느 인스턴스가 받아도 검증할 수 있다. 같은 출처라 쿠키는 두 서비스가 공유한다.
  - 프론트엔드는 앱을 열 때 `XSRF-TOKEN` 쿠키가 없으면 `GET /api/auth/csrf`(인증 불필요, §3.1)를 한 번 호출한다. 로그인 직후에도 쿠키 값이 바뀔 수 있으므로 변경 요청마다 쿠키의 현재 값을 읽어 헤더에 넣는다.
  - 토큰이 없거나 다르면 403 `CSRF_TOKEN_INVALID`를 반환한다. 프론트엔드는 `GET /api/auth/csrf`를 다시 호출한 뒤 같은 요청을 한 번만 재시도한다.
  - `SameSite=Lax`는 추가 방어로 유지한다. 단독 방어로 보지 않는다.
- 비밀번호, 세션 ID, 인증 토큰 원문은 로그에 남기지 않는다. (`NFR-010`)

### 2.3 날짜·시간·숫자

| 종류 | 형식 | 예 | 설명 |
|---|---|---|---|
| 날짜 | `YYYY-MM-DD` | `2026-11-12` | 수행일, 기한, 목표 기간, 달력 날짜. 시간대 없는 달력 날짜다 |
| 월 | `YYYY-MM` | `2026-11` | 월간 목표, 월간 리포트 |
| 분기 | `YYYY-Qn` | `2026-Q4` | 분기 리포트 |
| 일시 | ISO-8601 UTC | `2026-11-12T01:23:45Z` | 생성·수정·완료·삭제 시각. DB에도 UTC로 저장한다 |
| 진행률 | 숫자, 소수점 둘째 자리 | `66.67` | 0.00~100.00. 셋째 자리에서 HALF_UP 반올림 |
| 연간 달성률 | 정수 | `20` | 확정된 기준 중 달성한 기준의 배점 합 |

- `오늘`, `이번 주`, `이번 달`, 남은 일수는 서버가 계정 시간대(`user_settings.timezone`)와 주 시작 요일로 계산한다. 클라이언트 시계는 쓰지 않는다. (`PLAN-004`)
- 날짜 값은 시간대와 무관한 `DATE`로 저장한다. 사용자가 시간대를 바꿔도 이미 저장한 수행일·기한·목표 기간은 바뀌지 않는다. 바뀐 시간대는 이후의 `오늘` 판정에만 적용한다. (`FN-SET-02`)
- 프론트엔드는 화면 표시용으로 날짜를 `2026년`, `2026.11`, `2026.11.12` 형식으로 바꾼다. (`GOAL-010`)

### 2.4 동시 수정 방지 (`version`)

- 사용자가 직접 수정하는 리소스는 `version`(정수, 0부터 시작)을 갖는다. 대상은 연간·월간·주간 목표, 행동, 달력 항목, 설정, 온보딩 임시 저장, 직접 회고다. 서버는 JPA `@Version`으로 처리한다. (`PROG-008`)
- `PATCH`·`PUT` 수정 요청은 조회 때 받은 `version`을 함께 보낸다. 서버의 값과 다르면 409 `VERSION_CONFLICT`를 반환하고 변경하지 않는다. 클라이언트는 최신 값을 다시 조회해 사용자에게 보여준다.
- 완료 처리·기준 달성처럼 "목표 상태를 지정"하는 요청은 같은 요청을 반복해도 결과가 같으므로 `version`을 받지 않는다. 진행률 가산·차감은 해당 트랜잭션 안에서 원자적으로 처리한다.
- 목표의 `version`은 목표 자체 필드를 바꿀 때 오른다. 하위 행동 완료로 진행률만 다시 계산할 때는 오르지 않는다. 그래서 행동을 완료해도 열려 있는 목표 수정 화면이 충돌하지 않는다.
- 서버가 계산하는 `weekly_goals.current_value`, `weekly_goals.progress_rate`, `monthly_goals.progress_rate`는 엔티티 저장이 아니라 직접 UPDATE로만 바꾸고, 엔티티에서는 `@Column(updatable = false)`로 읽기 전용 매핑한다. 직접 UPDATE는 `version`을 건드리지 않는다. 이유와 동시 완료 처리 방식은 ERD 결정 16·17을 따른다.

### 2.5 소유권과 삭제된 리소스

- 모든 조회·변경은 세션의 `userId`가 소유한 데이터로 한정한다. 다른 사용자의 리소스는 존재 여부를 드러내지 않도록 404 `RESOURCE_NOT_FOUND`를 반환한다. (`GOAL-004`)
- 소프트 삭제된 목표와, 상위 목표 삭제로 숨겨진 하위 목표·미완료 행동도 일반 API에서는 404로 취급한다. 휴지통 조회와 복원 API만 이를 다룬다.
- 30일이 지나 과거 이력으로 남은 목표는 리포트에서만 집계한다. 새 하위 목표나 행동의 부모로 지정할 수 없다. (`FN-GOAL-05`)

### 2.6 인스턴스 식별자

`goal-service`의 모든 응답에는 `X-Instance-Id` 헤더가 붙는다. 값은 처리한 컨테이너의 호스트명(예: `aim2day-goal-service-2`)이다. 시연할 때 브라우저 개발자 도구에서 요청마다 값이 바뀌는 것을 확인한다. (`DIST-003`)

### 2.7 목표 공통 표현

목표·행동 응답에서 반복해 쓰는 필드다.

| 필드 | 값 | 설명 |
|---|---|---|
| `status` | `PROGRESS`·`COMPLETED`·`ON_HOLD`·`DROPPED` | 목표에 저장된 상태 |
| `pathStatus` | `ON_HOLD`·`DROPPED`·`null` | 자신 또는 가장 가까운 상위 목표의 보류·중단 상태. 화면은 이름 옆에 `(보류)`·`(중단)`을 붙인다. 숨기지 않는다 (`GOAL-009`) |
| `isDelayed` | boolean | 종료일이 오늘보다 앞서고 `status`가 `COMPLETED`가 아닌 경우 `true`. 저장 상태는 바꾸지 않는다 (`DASH-004`) |
| `color` | `#RRGGBB`·`null` | 저장된 색. 월간·주간은 `null`이면 부모 색을 상속한다 |
| `displayColor` | `#RRGGBB` | 상속을 반영해 화면에 쓸 색 (`GOAL-011`) |
| `progress` | `{ "state": "RECORDED", "rate": 66.67 }` | 주간·월간 실행률. 계산할 하위 항목이 없으면 `{ "state": "NO_RECORD", "rate": null }`이고 화면은 `기록 없음`을 표시한다 |

### 2.8 HTTP 상태 코드와 오류 코드

| HTTP | `code` | 상황 | `details` |
|---|---|---|---|
| 400 | `MALFORMED_REQUEST` | JSON 파싱 실패, 알 수 없는 열거값 | `null` |
| 400 | `VALIDATION_FAILED` | 필수값 누락, 길이·형식·범위 위반 | `{ "fieldErrors": [{ "field": "email", "reason": "INVALID_FORMAT" }] }` |
| 400 | `INVALID_TOKEN` | 인증·재설정 토큰이 만료·사용·변조됨. 세 경우를 구분하지 않는다 | `null` |
| 401 | `UNAUTHENTICATED` | 세션 없음·만료·무효 | `null` |
| 401 | `INVALID_CREDENTIALS` | 이메일 또는 비밀번호 불일치 | `null` |
| 403 | `EMAIL_NOT_VERIFIED` | 이메일 미인증 계정의 로그인 | `{ "email": "user@example.com" }` |
| 403 | `CSRF_TOKEN_INVALID` | 상태 변경 요청에 `X-XSRF-TOKEN` 헤더가 없거나 `XSRF-TOKEN` 쿠키와 다름 | `null` |
| 404 | `RESOURCE_NOT_FOUND` | 없음, 다른 사용자 소유, 삭제·숨김 상태 | `{ "resource": "WEEKLY_GOAL", "id": 31 }` |
| 409 | `EMAIL_ALREADY_EXISTS` | 가입된 이메일로 가입 | `null` |
| 409 | `VERSION_CONFLICT` | `version` 불일치 | `{ "currentVersion": 4 }` |
| 409 | `GOAL_LIMIT_EXCEEDED` | 연간 목표 생성·복원 시 활성 목표가 이미 3개 | `{ "year": 2026, "current": 3, "max": 3 }` |
| 409 | `DELETE_CONFIRMATION_REQUIRED` | 하위 항목이 있는 목표를 확인 없이 삭제 | §4.7 참고 |
| 409 | `PARENT_GOAL_DELETED` | 부모가 삭제 상태인 목표의 복원 | `{ "parentLevel": "MONTHLY", "parentId": 8 }` |
| 409 | `RESTORE_PERIOD_EXPIRED` | 삭제 후 30일이 지난 목표의 복원 | `{ "deletedAt": "...", "restoreDeadline": "..." }` |
| 409 | `CRITERIA_ALREADY_CONFIRMED` | 이미 확정된 기준의 재확정 | `null` |
| 422 | `PARENT_GOAL_UNAVAILABLE` | 부모 목표가 없음·타인 소유·삭제·과거 이력 | `{ "parentLevel": "YEARLY", "parentId": 3 }` |
| 422 | `PARENT_PERIOD_MISMATCH` | 하위 목표 기간이 부모 기간과 겹치지 않음 | `{ "parentStartDate": "...", "parentEndDate": "..." }` |
| 422 | `WEEKLY_GOAL_REQUIRED` | 선택한 월간 목표 아래에 행동의 수행일을 기간에 포함하는 주간 목표가 없음 | `{ "monthlyGoalId": 8, "performedDate": "2026-11-12" }` |
| 422 | `WEEKLY_GOAL_SELECTION_REQUIRED` | 연결 가능한 주간 목표가 여러 개 | `{ "candidates": [{ "id": 31, "title": "...", "startDate": "...", "endDate": "..." }] }` |
| 422 | `CRITERIA_WEIGHT_SUM_INVALID` | 확정·개정 시 배점 합계가 100이 아님 | `{ "totalWeight": 90, "required": 100 }` |
| 422 | `CRITERIA_NOT_CONFIRMED` | 확정 전 기준의 달성 처리 | `null` |
| 422 | `FUTURE_ACTION_COMPLETION_BLOCKED` | 수행일이 미래인 행동의 완료, 완료된 행동의 수행일을 미래로 변경 | `{ "today": "2026-11-12", "performedDate": "2026-11-13" }` |
| 422 | `GOAL_ACTION_DATE_OUT_OF_RANGE` | 목표 연결 행동의 수행일이 현재 주 이후(결정 17) | `{ "currentWeekEndDate": "2026-11-14", "performedDate": "2026-11-20" }` |
| 422 | `QUADRANT_OUT_OF_RANGE` | 목표 연결 행동의 x < 50 | `{ "minImportance": 50 }` |
| 422 | `CLASSIFICATION_DATE_INVALID` | 오늘이 아닌 행동의 분류 | `{ "today": "2026-11-12", "performedDate": "2026-11-11" }` |
| 422 | `NOT_NUMERIC_GOAL` | 체크형 주간 목표에 수치 기록·기여량 지정 | `null` |
| 422 | `MEASUREMENT_TYPE_IMMUTABLE` | 생성 후 측정 방식 변경 | `null` |
| 422 | `RECORD_NOT_DELETABLE` | 행동 완료로 생긴 수치 기록을 직접 삭제 | `null` |
| 422 | `GOAL_ORDER_MISMATCH` | 순서 저장 목록과 활성 목표 집합이 다름 | `{ "expectedIds": [3, 5, 9] }` |
| 422 | `ITEM_TYPE_IMMUTABLE` | 달력 항목 종류 변경 | `null` |
| 422 | `INVALID_PERIOD` | 존재하지 않는 리포트 기간 또는 너무 긴 조회 범위 | `{ "maxDays": 100 }` |
| 429 | `TOO_MANY_REQUESTS` | 같은 이메일로 메일 재요청 간격(60초) 미만 | `{ "retryAfterSeconds": 42 }` |
| 500 | `INTERNAL_ERROR` | 예상하지 못한 서버 오류 | `null` |
| 502 | `MAIL_SEND_FAILED` | 회원가입 직후 메일 서버 전송 실패. 가입 API에서만 쓴다. 재발송·재설정 API는 실패해도 200을 반환한다(결정 15) | `{ "accountCreated": true }` |

`VALIDATION_FAILED`의 `reason` 값은 `REQUIRED`, `INVALID_FORMAT`, `TOO_LONG`, `OUT_OF_RANGE`, `PASSWORD_POLICY`, `PASSWORD_MISMATCH`를 쓴다.

409는 현재 상태와 충돌하는 요청, 422는 형식은 맞지만 업무 규칙을 어긴 요청이다.

## 3. user-service

### 3.1 인증

입력 규칙은 다음과 같다.

| 필드 | 규칙 |
|---|---|
| `email` | 이메일 형식, 최대 255자. 앞뒤 공백을 지우고 소문자로 바꿔 저장·비교 |
| `username` | 공백 제외 1자 이상, 최대 50자. 다른 사용자와 같아도 됨 |
| `password` | 8~64자. 영문, 숫자, 특수문자(영문·숫자·공백이 아닌 출력 가능한 ASCII 문자)를 각각 1자 이상 포함 |

메일 링크와 토큰은 다음과 같이 처리한다. 저장 방식은 ERD의 Redis 임시 인증 토큰 절을 따른다.

| 목적 | 링크 | Redis 키 | 만료 |
|---|---|---|---|
| 이메일 인증 | `{BASE_URL}/verify-email.html?token={token}` | `auth:verify:{SHA-256(token)}` | 24시간 |
| 비밀번호 재설정 | `{BASE_URL}/reset-password.html?token={token}` | `auth:reset:{SHA-256(token)}` | 30분 |

- 토큰은 32바이트 난수를 Base64URL로 인코딩한 값이다.
- 새 토큰을 발급하면 같은 사용자의 같은 목적 토큰은 지운다. 마지막 링크만 유효하다.
- 링크를 여는 페이지는 토큰을 바로 소비하지 않는다. 사용자가 확인 버튼이나 새 비밀번호 제출을 눌러 API를 호출할 때 `GETDEL`로 소비한다.
- 메일 재요청은 이메일별로 60초에 한 번만 허용한다(Redis 키 `auth:mail-throttle:{purpose}:{SHA-256(email)}`, TTL 60초).

#### GET `/api/auth/csrf`

CSRF 토큰 쿠키 `XSRF-TOKEN`을 내려준다. 인증이 필요 없고, 로그인 전 화면(로그인·가입·재설정)이 첫 변경 요청 전에 호출한다. 200과 `data: null`을 반환한다. (`NFR-009`, 결정 13)

#### POST `/api/auth/signup`

회원가입 후 인증 메일을 보낸다. (`AUTH-001`, `AUTH-006`, `AUTH-007`)

```json
{
  "email": "user@example.com",
  "username": "정수",
  "password": "Aim2day!2026",
  "passwordConfirm": "Aim2day!2026"
}
```

201 응답:

```json
{ "userId": 7, "email": "user@example.com", "verificationMailSent": true }
```

- 비밀번호는 BCrypt로 해시해 저장한다. `is_verified=false`로 만든다.
- 계정 저장 후 메일 전송에 실패하면 502 `MAIL_SEND_FAILED`와 `details.accountCreated=true`를 반환한다. 화면은 가입이 끝난 것처럼 보이지 않게 실패를 알리고 `[인증 메일 다시 보내기]`를 제공한다.
- 오류: 400 `VALIDATION_FAILED`(`PASSWORD_POLICY`, `PASSWORD_MISMATCH` 포함), 403 `CSRF_TOKEN_INVALID`, 409 `EMAIL_ALREADY_EXISTS`, 502 `MAIL_SEND_FAILED`
- 가입 API는 이미 409로 가입 여부를 드러내므로 메일 실패도 숨기지 않는다. 사용자가 인증 메일을 다시 요청해야 하기 때문이다. (결정 15)

#### POST `/api/auth/verification-emails`

인증 메일을 다시 보낸다.

```json
{ "email": "user@example.com" }
```

- 가입 여부나 인증 여부와 상관없이 200과 `data: null`을 반환한다. 다른 사람의 가입 여부를 알아낼 수 없게 하기 위해서다. 메일은 미인증 계정에만 보낸다.
- 메일 전송이 실패해도 200을 반환한다. 실패는 서버 로그에만 남기고(사용자 ID만 기록, 토큰 원문·이메일은 남기지 않음 `NFR-010`) 응답에 드러내지 않는다. 사용자는 60초 뒤 다시 요청할 수 있다. (결정 15)
- 오류: 400 `VALIDATION_FAILED`, 403 `CSRF_TOKEN_INVALID`, 429 `TOO_MANY_REQUESTS`

#### POST `/api/auth/email-verification`

인증 링크의 토큰으로 이메일 인증을 마친다. (`FN-AUTH-02`)

```json
{ "token": "q4n0...Base64URL" }
```

200 응답: `{ "email": "user@example.com" }`

- 오류: 400 `INVALID_TOKEN`, 403 `CSRF_TOKEN_INVALID`

#### POST `/api/auth/login`

이메일과 비밀번호로 로그인한다. (`AUTH-002`, `AUTH-003`)

```json
{ "email": "user@example.com", "password": "Aim2day!2026" }
```

200 응답: `{ "id": 7, "email": "user@example.com", "username": "정수" }` 와 `Set-Cookie: SESSION=...`

- 로그인 성공 시 세션 ID를 새로 발급한다(세션 고정 공격 방지).
- 비밀번호가 맞지만 미인증 계정이면 403 `EMAIL_NOT_VERIFIED`를 반환한다. 화면은 `이메일 인증 후 로그인할 수 있습니다.`와 `[인증 메일 다시 보내기]`를 표시한다.
- 오류: 400 `VALIDATION_FAILED`, 401 `INVALID_CREDENTIALS`, 403 `EMAIL_NOT_VERIFIED`, 403 `CSRF_TOKEN_INVALID`
- 로그인에 성공하면 세션 속성 `PRINCIPAL_NAME_INDEX_NAME`에 `userId`를 문자열로 저장한다(§2.2). 로그인 전 요청이라도 CSRF 토큰은 필요하다. 공격자 사이트가 피해자 브라우저를 공격자 계정으로 로그인시키는 로그인 CSRF를 막기 위해서다.

로그인 후 프론트엔드는 `GET /api/goals/onboarding/status`로 온보딩과 대시보드 중 이동할 곳을 정한다.

#### POST `/api/auth/logout`

Redis 세션을 즉시 지우고 쿠키를 만료시킨다. 세션이 없어도 200을 반환한다. (`AUTH-004`, `AUTH-009`)

#### POST `/api/auth/password-reset-requests`

비밀번호 재설정 링크를 요청한다. 요청 본문은 `{ "email": "..." }`이다. 가입 여부와 상관없이 200과 `data: null`을 반환하고, 인증을 마친 계정에만 메일을 보낸다.

- 메일 전송이 실패해도 200을 반환한다. 실패는 사용자 ID만 로그에 남기고 응답에 드러내지 않는다. 사용자는 60초 뒤 다시 요청할 수 있다. (결정 15)
- 오류: 400 `VALIDATION_FAILED`, 403 `CSRF_TOKEN_INVALID`, 429 `TOO_MANY_REQUESTS`

#### POST `/api/auth/password-reset`

새 비밀번호를 저장한다. (`AUTH-008`)

```json
{ "token": "x8Pz...", "newPassword": "NewPass!2026", "newPasswordConfirm": "NewPass!2026" }
```

- 토큰을 소비하고 새 BCrypt 해시를 저장한다. 이후 이전 비밀번호로는 로그인할 수 없다.
- 해당 사용자의 기존 세션을 모두 무효화한다. `FindByIndexNameSessionRepository.findByPrincipalName(userId)`로 그 사용자의 세션을 모두 찾아 각각 삭제한다(§2.2의 인덱스형 저장소). 다른 기기의 로그인도 끝난다. (결정 14)
- 토큰 형식은 맞지만 새 비밀번호가 규칙에 맞지 않으면 토큰을 소비하지 않고 400 `VALIDATION_FAILED`를 반환한다. 입력 검증을 토큰 소비보다 먼저 한다.
- 오류: 400 `VALIDATION_FAILED`, 400 `INVALID_TOKEN`, 403 `CSRF_TOKEN_INVALID`

### 3.2 사용자

#### GET `/api/users/me`

상단 계정 영역과 설정 화면의 기본 정보를 조회한다. (`AUTH-005`, `AUTH-009`)

```json
{ "id": 7, "email": "user@example.com", "username": "정수", "createdAt": "2026-11-01T03:00:00Z" }
```

- 세션이 없으면 401 `UNAUTHENTICATED`를 반환한다. 프론트엔드는 이 응답으로 비로그인 계정 영역(로그인·회원가입)을 표시한다.

## 4. goal-service

### 4.1 설정

#### GET `/api/settings`

설정과, 서버가 계정 시간대로 계산한 오늘·이번 주·이번 달을 함께 준다. 설정 행이 없으면 기본값(SUNDAY, Asia/Seoul)으로 만든 뒤 반환한다. 프론트엔드는 날짜 기본값과 기간 표시에 이 값을 쓴다.

```json
{
  "weekStartDay": "SUNDAY",
  "timezone": "Asia/Seoul",
  "version": 0,
  "today": "2026-11-12",
  "currentWeek": { "startDate": "2026-11-08", "endDate": "2026-11-14" },
  "currentMonth": { "month": "2026-11", "startDate": "2026-11-01", "endDate": "2026-11-30" }
}
```

#### PUT `/api/settings`

```json
{ "weekStartDay": "MONDAY", "timezone": "Asia/Seoul", "version": 0 }
```

- `weekStartDay`: `SUNDAY`~`SATURDAY`. `timezone`: Java `ZoneId`가 인식하는 IANA 이름.
- 이미 만든 주간 목표의 기간과 행동의 수행일은 바꾸지 않는다. 새로 만드는 주간 목표부터 적용한다. (`PLAN-001`)
- 200 응답은 `GET`과 같은 형식이다.
- 오류: 400 `VALIDATION_FAILED`, 409 `VERSION_CONFLICT`

### 4.2 온보딩

온보딩 네 질문은 진행하는 동안 임시 저장에만 기록한다. `[계획 완료]`를 누를 때 한 트랜잭션으로 목표와 행동을 만든다. 중간에 이탈해도 실제 목표가 반쯤 만들어진 상태로 남지 않는다.

#### GET `/api/goals/onboarding/status`

```json
{
  "needsOnboarding": true,
  "hasDraft": false,
  "currentYear": 2026,
  "activeYearlyGoalCount": 0,
  "maxYearlyGoalCount": 3,
  "firstQuestion": "올해 남은 기간에 어떤 일에 집중하고 싶나요?"
}
```

- `needsOnboarding`은 올해 활성 연간 목표가 0개이거나 임시 저장이 있으면 `true`다. 활성 목표 수는 상태와 무관하게 소프트 삭제되지 않은 목표를 센다.
- `firstQuestion`은 계정 시간대 기준 1월이면 `올해 어떤 일에 집중하고 싶나요?`, 2~12월이면 `올해 남은 기간에 어떤 일에 집중하고 싶나요?`다.

#### GET·PUT·DELETE `/api/goals/onboarding/draft`

- `GET`: 임시 저장이 없으면 `data: null`
- `PUT`: 저장하거나 덮어쓴다. 처음 저장할 때는 `version`을 생략한다.

```json
{
  "currentStep": 2,
  "draftData": { "yearlyGoals": [{ "tempId": "y1", "title": "백엔드 개발자 취업 준비" }] },
  "version": 3
}
```

- `currentStep`은 1~4다. `draftData`는 프론트엔드가 정하는 자유 JSON이며 최대 64KB다. 서버는 내용을 해석하지 않는다.
- `DELETE`: 임시 저장을 지운다. 없어도 200이다.
- 오류: 400 `VALIDATION_FAILED`, 409 `VERSION_CONFLICT`

#### POST `/api/goals/onboarding/complete`

네 단계 입력을 한 번에 저장한다. 새로 만드는 항목은 `tempId`로 서로 참조하고, 기존 목표는 실제 `id`로 참조한다. 부모 지정은 `...TempId`와 `...Id` 중 하나만 쓴다.

```json
{
  "yearlyGoals": [
    { "tempId": "y1", "title": "백엔드 개발자 취업 준비" }
  ],
  "monthlyGoals": [
    { "tempId": "m1", "yearlyGoalTempId": "y1", "title": "알고리즘 기초 다지기", "description": "자료구조 복습" }
  ],
  "weeklyGoals": [
    { "tempId": "w1", "monthlyGoalTempId": "m1", "title": "알고리즘 문제 10개 풀기", "measurementType": "NUMERIC", "targetValue": 10 }
  ],
  "actions": [
    { "type": "GOAL", "weeklyGoalTempId": "w1", "title": "알고리즘 문제 2개 풀기", "performedDate": "2026-11-12", "contributionValue": 2 },
    { "type": "MAINTENANCE", "title": "장보기" }
  ]
}
```

201 응답: 만든 항목의 `tempId → id` 대응표

```json
{ "yearlyGoals": { "y1": 3 }, "monthlyGoals": { "m1": 8 }, "weeklyGoals": { "w1": 31 }, "actions": [101, 102] }
```

- 각 항목의 규칙은 개별 생성 API(§4.3, §4.5, §4.6, §4.9)와 같다. 월간은 현재 달, 주간은 현재 주로 만든다. 연간 목표는 현재 연도로 만든다.
- 사용자 행을 먼저 잠그고 연간 목표 한도를 검사한다. 하나라도 실패하면 전체를 되돌린다.
- 성공하면 임시 저장을 지운다.
- 오류: 400 `VALIDATION_FAILED`(잘못된 `tempId` 참조 포함), 409 `GOAL_LIMIT_EXCEEDED`, 422 `PARENT_GOAL_UNAVAILABLE`, 422 `PARENT_PERIOD_MISMATCH`

### 4.3 연간 목표

연간 목표 응답 객체:

```json
{
  "id": 3,
  "targetYear": 2026,
  "title": "백엔드 개발자 취업 준비",
  "description": "포트폴리오와 코딩 테스트 준비",
  "color": "#3B82F6",
  "displayColor": "#3B82F6",
  "displayOrder": 0,
  "status": "PROGRESS",
  "pathStatus": null,
  "isDelayed": false,
  "criteria": {
    "state": "CONFIRMED",
    "achievementRate": 20,
    "initialBaselineRate": 10,
    "confirmedAt": "2026-11-03T10:00:00Z",
    "lastRevisedAt": "2026-11-10T10:00:00Z"
  },
  "version": 2,
  "createdAt": "2026-11-01T03:00:00Z",
  "updatedAt": "2026-11-10T10:00:00Z"
}
```

- 기준을 확정하기 전에는 `criteria.state`가 `NOT_CONFIGURED`이고 `achievementRate`, `initialBaselineRate`가 `null`이다. 화면은 퍼센트 대신 `달성 기준 설정하기`를 표시한다. (`GOAL-006`)

#### GET `/api/goals/yearly?year=2026`

`year`를 생략하면 현재 연도다. 활성 목표를 `displayOrder`, `id` 순으로 반환한다.

```json
{ "year": 2026, "activeCount": 2, "maxCount": 3, "items": [ /* 연간 목표 객체 */ ] }
```

#### POST `/api/goals/yearly`

```json
{ "targetYear": 2026, "title": "백엔드 개발자 취업 준비", "description": null, "color": "#3B82F6" }
```

- `targetYear`는 생략하면 현재 연도다. 현재 연도와 다음 연도만 허용한다(12월의 새해 계획용).
- `title` 1~100자, `color`는 `#RRGGBB`이며 생략하면 `#3B82F6`이다. 새 목표의 `displayOrder`는 그 해 목록의 마지막이다.
- 사용자 행 잠금 후 활성 목표가 3개 미만일 때만 만든다. (`GOAL-001`, ERD §4.1)
- 오류: 400 `VALIDATION_FAILED`, 409 `GOAL_LIMIT_EXCEEDED`

#### PUT `/api/goals/yearly/order`

```json
{ "year": 2026, "goalIds": [5, 3, 9] }
```

- `goalIds`는 그 해 활성 목표 전체를 빠짐없이 한 번씩 담아야 한다. 순서대로 `displayOrder`를 0부터 다시 매긴다. (`DASH-008`)
- 오류: 422 `GOAL_ORDER_MISMATCH`

#### GET `/api/goals/yearly/{id}`

연간 목표 객체에 하위 트리를 더해 반환한다. 삭제되지 않은 하위 목표만 포함한다.

```json
{
  "goal": { /* 연간 목표 객체 */ },
  "monthlyGoals": [
    {
      /* 월간 목표 객체 (§4.5) */
      "weeklyGoals": [ /* 주간 목표 객체 (§4.6) */ ]
    }
  ]
}
```

#### PATCH `/api/goals/yearly/{id}`

```json
{ "title": "백엔드 취업", "color": "#10B981", "status": "ON_HOLD", "version": 2 }
```

- 바꿀 필드만 보낸다. 바꿀 수 있는 필드는 `title`, `description`, `color`, `status`다. `targetYear`는 바꿀 수 없다.
- 오류: 400 `VALIDATION_FAILED`, 404 `RESOURCE_NOT_FOUND`, 409 `VERSION_CONFLICT`

#### DELETE `/api/goals/yearly/{id}?confirm=true`

§4.7의 공통 삭제 규칙을 따른다.

### 4.4 달성 기준

#### GET `/api/goals/yearly/{id}/criteria`

```json
{
  "state": "CONFIRMED",
  "achievementRate": 20,
  "initialBaselineRate": 10,
  "totalWeight": 100,
  "confirmedAt": "2026-11-03T10:00:00Z",
  "lastRevisedAt": "2026-11-10T10:00:00Z",
  "goalVersion": 2,
  "items": [
    { "id": 1, "text": "포트폴리오 프로젝트 2개 배포", "weight": 40, "isAchieved": false, "achievedAt": null, "isBaselineAchieved": false },
    { "id": 2, "text": "정보처리기사 취득", "weight": 10, "isAchieved": true, "achievedAt": "2026-11-03T10:00:00Z", "isBaselineAchieved": true }
  ]
}
```

#### PUT `/api/goals/yearly/{id}/criteria`

기준 목록 전체를 보낸 목록으로 바꾼다. `id`가 있는 항목은 수정하고, 없는 항목은 새로 만들며, 목록에서 빠진 기존 항목은 지운다.

```json
{
  "goalVersion": 2,
  "items": [
    { "id": 1, "text": "포트폴리오 프로젝트 2개 배포", "weight": 50 },
    { "text": "코딩 테스트 3회 통과", "weight": 50 }
  ],
  "memo": "자격증 기준을 코딩 테스트로 교체"
}
```

- `text` 1~255자, `weight` 1~100의 정수, 항목은 1~20개다. 연간 목표의 `version`을 `goalVersion`으로 보내 동시 편집을 막는다. 저장하면 목표의 `version`이 오른다.
- **확정 전**: 초안 저장이다. 배점 합계를 검사하지 않는다.
- **확정 후**: 개정이다. 배점 합계가 정확히 100이어야 한다. 기존 항목의 달성 여부는 유지하고 새 항목은 미달성으로 만든다. 달성률을 다시 계산하고, 같은 트랜잭션에서 `goal_progress_logs`에 `CRITERIA_REVISION`을 남긴다(달성률이 그대로여도 기록). `criteria_last_revised_at`을 갱신한다. (ERD §4.2)
- 응답은 `GET`과 같은 형식이다.
- 오류: 400 `VALIDATION_FAILED`, 409 `VERSION_CONFLICT`, 422 `CRITERIA_WEIGHT_SUM_INVALID`

#### POST `/api/goals/yearly/{id}/criteria/confirm`

저장된 초안을 확정하고 최초 기준선을 저장한다. (`FN-CRIT-01`)

```json
{ "goalVersion": 3, "baselineAchievedCriterionIds": [2] }
```

- 기준이 1개 이상이고 배점 합계가 정확히 100이어야 한다.
- `baselineAchievedCriterionIds`의 기준을 이미 달성한 것으로 표시하고(`is_achieved`, `is_baseline_achieved`), 그 배점 합을 `initial_baseline_rate`로 저장한다. 비우면 0%다.
- `goal_progress_logs`에 최초 관측값(이전 값 0, 새 값 = 기준선)을 `PROGRESS_UPDATE`로 남긴다.
- 응답은 `GET`과 같은 형식이다.
- 오류: 409 `CRITERIA_ALREADY_CONFIRMED`, 409 `VERSION_CONFLICT`, 422 `CRITERIA_WEIGHT_SUM_INVALID`

#### PUT `/api/goals/yearly/{id}/criteria/{criterionId}/achievement`

```json
{ "achieved": true, "memo": "첫 프로젝트 배포 완료" }
```

- 같은 값을 다시 보내면 아무것도 바꾸지 않고 200을 반환한다. 값이 바뀔 때만 배점을 더하거나 빼고 `goal_progress_logs`에 기록한다. (`FN-CRIT-02`, `PROG-007`)
- 행동 완료나 주간 진행률로 기준을 자동 달성하지 않는다.
- 응답: `{ "criterionId": 1, "isAchieved": true, "achievementRate": 60 }`
- 오류: 404 `RESOURCE_NOT_FOUND`, 422 `CRITERIA_NOT_CONFIRMED`

### 4.5 월간 목표

월간 목표 객체:

```json
{
  "id": 8,
  "yearlyGoal": { "id": 3, "title": "백엔드 개발자 취업 준비" },
  "month": "2026-11",
  "startDate": "2026-11-01",
  "endDate": "2026-11-30",
  "title": "알고리즘 기초 다지기",
  "description": "자료구조 복습",
  "color": null,
  "displayColor": "#3B82F6",
  "status": "PROGRESS",
  "pathStatus": null,
  "isDelayed": false,
  "progress": { "state": "RECORDED", "rate": 66.67 },
  "weeklyGoalCount": 2,
  "version": 0
}
```

#### GET `/api/goals/monthly?month=2026-11&yearlyGoalId=3`

`month`를 생략하면 현재 달이다. `yearlyGoalId`는 선택이다. 연간 목표의 `displayOrder`, 월간 목표 `id` 순으로 반환한다.

#### POST `/api/goals/monthly`

```json
{ "yearlyGoalId": 3, "title": "알고리즘 기초 다지기", "description": "자료구조 복습", "color": null }
```

- 대상 월은 받지 않는다. 계정 시간대 기준 현재 달의 첫날~말일로 만든다. (`GOAL-014`)
- 부모 연간 목표의 연도가 현재 연도여야 한다.
- 오류: 400 `VALIDATION_FAILED`, 422 `PARENT_GOAL_UNAVAILABLE`, 422 `PARENT_PERIOD_MISMATCH`

#### GET `/api/goals/monthly/{id}`

월간 목표 객체와 하위 `weeklyGoals` 배열을 반환한다.

#### PATCH `/api/goals/monthly/{id}`

바꿀 수 있는 필드는 `title`, `description`, `color`, `status`다. 귀속 월과 부모 연간 목표는 바꿀 수 없다. 응답과 오류는 연간 목표 수정과 같다.

#### DELETE `/api/goals/monthly/{id}?confirm=true`

§4.7을 따른다.

### 4.6 주간 목표와 수치 기록

주간 목표 객체:

```json
{
  "id": 31,
  "path": {
    "yearlyGoal": { "id": 3, "title": "백엔드 개발자 취업 준비" },
    "monthlyGoal": { "id": 8, "title": "알고리즘 기초 다지기", "month": "2026-11" }
  },
  "startDate": "2026-11-08",
  "endDate": "2026-11-14",
  "title": "알고리즘 문제 10개 풀기",
  "description": null,
  "color": null,
  "displayColor": "#3B82F6",
  "measurementType": "NUMERIC",
  "targetValue": 10.00,
  "currentValue": 14.00,
  "isTargetReached": true,
  "weight": 1.00,
  "status": "PROGRESS",
  "pathStatus": null,
  "isDelayed": false,
  "progress": { "state": "RECORDED", "rate": 100.00 },
  "actionCounts": { "total": 5, "completed": 3 },
  "version": 1
}
```

- 체크형이면 `targetValue`, `currentValue`, `isTargetReached`가 `null`이다. 연결 행동이 0개면 `progress.state`가 `NO_RECORD`다.
- 수치형의 완료 색상은 `isTargetReached`(`currentValue >= targetValue`)로 정한다. 반올림한 진행률로 정하지 않는다. (ERD §4.3)

#### GET `/api/goals/weekly?date=2026-11-12&monthlyGoalId=8`

`date`가 기간에 들어가는 주간 목표를 반환한다. `date`를 생략하면 오늘이다. `monthlyGoalId`는 선택이며, 행동 추가 화면에서 연결 후보를 고를 때 쓴다.

#### POST `/api/goals/weekly`

```json
{
  "monthlyGoalId": 8,
  "title": "알고리즘 문제 10개 풀기",
  "description": null,
  "color": null,
  "measurementType": "NUMERIC",
  "targetValue": 10,
  "weight": 1
}
```

- 기간은 받지 않는다. 생성 시점의 현재 설정으로 계산한 **이번 주** 7일로 만든다.
- 부모 월간 목표의 기간과 이번 주가 하루라도 겹쳐야 한다. 월·연도 경계 주는 겹치는 두 월간 목표 중 하나를 고르며 나누지 않는다. (`GOAL-006`)
- `measurementType`은 `CHECKLIST`(기본) 또는 `NUMERIC`이다. `NUMERIC`이면 `targetValue`(0 초과, 소수점 둘째 자리까지)가 필수다. `weight`는 0 초과 999.99 이하이며 기본값은 1이다.
- 만든 뒤 부모 월간 목표의 진행률을 다시 계산한다(새 주간 목표는 0%로 분모에 들어간다).
- 오류: 400 `VALIDATION_FAILED`, 422 `PARENT_GOAL_UNAVAILABLE`, 422 `PARENT_PERIOD_MISMATCH`

#### GET `/api/goals/weekly/previous-summary`

주초 계획 화면에서 직전 주의 실제 진행(완료한 일과 남은 일)을 보여준다. 직전 주는 현재 주 시작일 7일 전부터 6일 뒤까지다. (`PLAN-003`)

```json
{
  "startDate": "2026-11-01",
  "endDate": "2026-11-07",
  "weeklyGoals": [
    {
      "id": 30,
      "title": "자료구조 정리",
      "path": { "yearlyGoal": { "id": 3, "title": "..." }, "monthlyGoal": { "id": 8, "title": "..." } },
      "progress": { "state": "RECORDED", "rate": 50.00 },
      "completedActions": [{ "id": 90, "title": "스택 정리", "performedDate": "2026-11-02" }],
      "remainingActions": [{ "id": 91, "title": "큐 정리", "performedDate": "2026-11-05" }]
    }
  ]
}
```

직전 주에 걸친 주간 목표가 없으면 `weeklyGoals`는 빈 배열이고 화면은 `기록 없음`을 표시한다.

#### GET `/api/goals/weekly/{id}`

주간 목표 객체와 연결 행동 `actions` 배열(§4.9 행동 객체)을 반환한다.

#### PATCH `/api/goals/weekly/{id}`

- 바꿀 수 있는 필드: `title`, `description`, `color`, `status`, `targetValue`, `weight`, `monthlyGoalId`
- `monthlyGoalId`는 이 주와 기간이 겹치는 다른 월간 목표로만 옮길 수 있다. 옮기면 이전·새 월간 목표의 진행률을 모두 다시 계산한다.
- `targetValue`, `weight`를 바꾸면 진행률을 다시 계산한다. 기간과 `measurementType`은 바꿀 수 없다.
- 오류: 400 `VALIDATION_FAILED`, 409 `VERSION_CONFLICT`, 422 `MEASUREMENT_TYPE_IMMUTABLE`, 422 `PARENT_GOAL_UNAVAILABLE`, 422 `PARENT_PERIOD_MISMATCH`

#### DELETE `/api/goals/weekly/{id}?confirm=true`

§4.7을 따른다.

#### GET `/api/goals/weekly/{id}/numeric-records`

```json
[
  { "id": 501, "source": "ACTION_COMPLETION", "action": { "id": 101, "title": "알고리즘 문제 2개 풀기" }, "amount": 2.00, "memo": null, "createdAt": "..." },
  { "id": 502, "source": "DIRECT_INPUT", "action": null, "amount": 3.00, "memo": "주말 스터디", "createdAt": "..." }
]
```

#### POST `/api/goals/weekly/{id}/numeric-records`

직접 입력한 실적을 **이번에 추가한 양**으로 기록한다. (`PROG-003`)

```json
{ "amount": 3, "memo": "주말 스터디" }
```

- `amount`는 0 초과다. 기록 삽입, `current_value` 가산, 주간·월간 진행률 재계산을 한 트랜잭션에서 처리한다. `current_value`는 `UPDATE weekly_goals SET current_value = current_value + :amount WHERE id = :id`로 DB에서 원자적으로 더하고, 진행률은 행을 `FOR UPDATE`로 잠근 뒤 다시 읽어 계산한다(§4.9 완료 항목, ERD 결정 16).
- 같은 작업을 행동 완료로도 적었는지는 서버가 판별하지 않는다. 직접 기록 화면에서 중복 입력을 안내한다.
- 201 응답: `{ "record": { /* 기록 */ }, "weeklyGoal": { /* 주간 목표 객체 */ } }`
- 오류: 400 `VALIDATION_FAILED`, 422 `NOT_NUMERIC_GOAL`

#### DELETE `/api/goals/weekly/{id}/numeric-records/{recordId}`

잘못 입력한 직접 기록을 지우고 `current_value`에서 뺀다. 기록 삭제와 차감(`current_value = current_value - :amount`, 원자적 UPDATE), 진행률 재계산은 한 트랜잭션에서 처리한다. 행동 완료로 생긴 기록은 행동의 완료 취소로만 지울 수 있다.

- 오류: 404 `RESOURCE_NOT_FOUND`, 422 `RECORD_NOT_DELETABLE`

### 4.7 목표 삭제·휴지통·복원

#### DELETE `/api/goals/{level}/{id}?confirm=true`

`level`은 `yearly`, `monthly`, `weekly`다. 경로는 §4.3·4.5·4.6과 같다.

- 숨겨질 하위 목표나 미완료 행동이 있는데 `confirm=true`가 없으면 삭제하지 않고 409 `DELETE_CONFIRMATION_REQUIRED`를 반환한다. 화면은 이 정보로 확인 창을 띄운다. (`GOAL-007`)

```json
{
  "code": "DELETE_CONFIRMATION_REQUIRED",
  "message": "하위 목표와 미완료 행동이 함께 숨겨집니다. 삭제할까요?",
  "details": {
    "monthlyGoalCount": 2,
    "weeklyGoalCount": 3,
    "incompleteActionCount": 4,
    "restoreDeadline": "2026-12-12T03:00:00Z",
    "notice": "30일 뒤 앞으로의 계획과 미완료 행동은 영구 삭제되고, 완료한 목표·행동과 달성한 기준은 과거 이력으로 남습니다."
  }
}
```

- 하위 항목이 없으면 `confirm` 없이 삭제한다.
- 삭제하면 `deleted_at`을 기록한다. 하위 목표와 그 경로의 미완료 행동은 숨기지만 연결과 데이터는 바꾸지 않는다. 부모 월간 목표의 진행률을 다시 계산한다(삭제된 주간 목표는 분모에서 빠진다). (`GOAL-008`, `GOAL-013`)
- 200 응답: `{ "deletedAt": "2026-11-12T03:00:00Z", "restoreDeadline": "2026-12-12T03:00:00Z" }`

#### GET `/api/goals/trash`

직접 삭제했고 복구 기한이 남은 목표를 단계와 관계없이 최근 삭제 순으로 반환한다. 상위 목표 삭제로 함께 숨겨진 하위 목표는 따로 나오지 않는다. 상위 목표를 복원하면 함께 돌아온다.

```json
[
  {
    "level": "YEARLY",
    "id": 3,
    "title": "백엔드 개발자 취업 준비",
    "periodLabel": "2026",
    "deletedAt": "2026-11-12T03:00:00Z",
    "restoreDeadline": "2026-12-12T03:00:00Z",
    "remainingDays": 30,
    "parent": null,
    "isParentDeleted": false
  }
]
```

`periodLabel`은 연간 `2026`, 월간 `2026-11`, 주간 `2026-11-08~2026-11-14`다.

#### POST `/api/goals/{level}/{id}/restore`

- 삭제 후 30일 이내이면 `deleted_at`을 지운다. 그 아래 숨겨졌던 하위 목표와 미완료 행동이 원래 연결대로 다시 보인다. 단, 하위 목표가 따로 삭제된 상태였다면 그 목표는 계속 삭제 상태다. (`FN-GOAL-04`)
- 연간 목표는 사용자 행을 잠근 뒤 한도를 다시 검사한다.
- 자동 정리가 아직 돌지 않았어도 기한이 지났으면 거부한다.
- 200 응답: 복원한 목표 객체
- 오류: 404 `RESOURCE_NOT_FOUND`, 409 `GOAL_LIMIT_EXCEEDED`, 409 `PARENT_GOAL_DELETED`, 409 `RESTORE_PERIOD_EXPIRED`

30일 뒤 정리는 API가 아니라 `goal-service`의 예약 작업이 처리한다. 세 인스턴스가 동시에 돌아도 같은 결과가 나오도록, 정리 대상 행을 `SELECT ... FOR UPDATE SKIP LOCKED`로 잠근 뒤 ERD §4.4 순서대로 처리한다.

### 4.8 진행 기록

#### GET `/api/goals/progress-logs?limit=10`

대시보드의 최근 진행 기록이다. `limit`은 1~50이며 기본값은 10이다. (`DASH-005`, `PROG-007`)

```json
[
  {
    "id": 900,
    "targetType": "WEEKLY_GOAL",
    "targetId": 31,
    "targetTitle": "알고리즘 문제 10개 풀기",
    "previousRate": 40.00,
    "newRate": 60.00,
    "changeType": "PROGRESS_UPDATE",
    "logDate": "2026-11-12",
    "memo": "행동 완료: 알고리즘 문제 2개 풀기",
    "createdAt": "2026-11-12T01:00:00Z"
  }
]
```

### 4.9 행동

행동 객체:

```json
{
  "id": 101,
  "type": "GOAL",
  "title": "알고리즘 문제 2개 풀기",
  "performedDate": "2026-11-12",
  "dueDate": "2026-11-14",
  "daysUntilDue": 2,
  "isCompleted": false,
  "completedAt": null,
  "contributionValue": 2.00,
  "resultSummary": null,
  "goalPath": {
    "yearlyGoal": { "id": 3, "title": "백엔드 개발자 취업 준비" },
    "monthlyGoal": { "id": 8, "title": "알고리즘 기초 다지기", "month": "2026-11" },
    "weeklyGoal": { "id": 31, "title": "알고리즘 문제 10개 풀기" }
  },
  "pathStatus": null,
  "displayColor": "#3B82F6",
  "placement": {
    "state": "RECLASSIFYING",
    "confirmed": { "importance": 80, "urgency": 90 },
    "pending": { "importance": 72, "urgency": 70 },
    "displayed": { "importance": 72, "urgency": 70 }
  },
  "version": 0
}
```

- `type`: `GOAL`(목표 연결) 또는 `MAINTENANCE`(`그 밖의 할 일`). `MAINTENANCE`는 `goalPath`, `contributionValue`가 `null`이다.
- `daysUntilDue`: 오늘 기준 남은 일수다. 0이면 `오늘`, 음수면 `기한 지남`으로 표시한다. 기한이 없으면 `null`이고 남은 일수를 표시하지 않는다.
- `placement`: 행동 목록을 조회한 날짜(`date`)의 분류다. `state`는 ERD 9번 테이블의 상태와 같다. 오늘이 아닌 날짜는 보기 전용이며, 그 날짜에 남아 있는 확정하지 않은 변경(`pending` 좌표, 되돌리기 대기)은 없는 것으로 본다. 확정 좌표가 있으면 `CONFIRMED`, 없으면 `UNCLASSIFIED`로 응답한다. (결정 18)

| `state` | `confirmed` | `pending` | `displayed`(화면의 점 위치) |
|---|---|---|---|
| `UNCLASSIFIED` | `null` | `null` | `null` (트레이) |
| `PENDING` | `null` | 좌표 | `pending` |
| `CONFIRMED` | 좌표 | `null` | `confirmed` |
| `RECLASSIFYING` | 좌표 | 좌표 | `pending` |
| `PENDING_UNCLASSIFY` | 좌표 | `null` | `null` (트레이) |

#### GET `/api/actions?date=2026-11-12&sort=IMPORTANCE_URGENCY`

수행일이 `date`인 행동을 반환한다. `date`를 생략하면 오늘이다. 삭제됐거나 상위 목표 삭제로 숨겨진 미완료 행동은 빠진다.

대시보드의 날짜 이동(`◀`·`▶`, 날짜 입력, `[오늘]`)은 `date`만 바꿔 이 API를 다시 호출한다. 지난·미래 날짜 모두 조회할 수 있다. (`DASH-011`, `FN-DASH-03`)

| 조회 날짜 | 행동 추가 | 완료 체크 | 사분면 분류 | `pastIncomplete` |
|---|---|---|---|---|
| 지난 날짜 | 가능 (`performedDate`=그 날짜). 목표 연결은 그 주의 주간 목표가 있을 때만(§4.9 `POST`) | 가능 | 보기만 | `null` |
| 오늘 | 가능 | 가능 | 가능 | 채움 |
| 미래 날짜 | 가능 (`performedDate`=그 날짜). 현재 주 이후 날짜는 `MAINTENANCE`만(결정 17) | 불가 (`FUTURE_ACTION_COMPLETION_BLOCKED`) | 보기만 | `null` |

| `sort` | 순서 |
|---|---|
| `IMPORTANCE_URGENCY` (기본) | x+y 내림차순 → x 내림차순 → `id` 오름차순. 미분류는 뒤 |
| `IMPORTANCE` | x 내림차순 → `id` 오름차순. 미분류는 뒤 |
| `URGENCY` | y 내림차순 → `id` 오름차순. 미분류는 뒤 |
| `GROUP` | `GOAL` 먼저, 그다음 `MAINTENANCE`. 각 그룹 안은 `id` 오름차순 |

정렬은 화면에 보이는 점 위치(`placement.displayed`)를 쓴다. `displayed`가 `null`이면 미분류로 본다. 정렬은 응답 순서만 바꾸며 저장된 값은 바꾸지 않는다. (`FOCUS-015`)

```json
{
  "date": "2026-11-12",
  "sort": "IMPORTANCE_URGENCY",
  "summary": {
    "goal": { "total": 2, "completed": 0 },
    "maintenance": { "total": 1, "completed": 0 }
  },
  "classification": { "unclassified": 1, "unconfirmedChanges": 1, "confirmed": 1, "confirmedToday": true },
  "items": [ /* 행동 객체 */ ],
  "pastIncomplete": {
    "count": 2,
    "items": [ /* 행동 객체. 수행일 오름차순, 같은 날은 id 오름차순 */ ]
  }
}
```

- `classification.unconfirmedChanges`는 확정하지 않은 변경이 있는 행동 수다(`PENDING`, `RECLASSIFYING`, `PENDING_UNCLASSIFY`). 오늘이 아닌 날짜는 항상 0이다. `confirmedToday`는 오늘 한 번이라도 분류를 확정했는지다. 화면은 이 값으로 그날 분류 안내를 줄인다.
- **`pastIncomplete`** (`FOCUS-017`): `date`가 오늘일 때만 채운다. 다른 날짜면 `null`이다. 수행일이 오늘보다 앞선 미완료 행동이다(`GOAL`·`MAINTENANCE` 모두). 삭제됐거나 상위 목표 삭제로 숨겨진 행동은 빠진다. 화면은 오늘 목록 아래 접힌 `지난 미완료 N개` 영역에 보여준다. 이 행동들은 `summary`의 완료 수와 사분면 분류에 포함하지 않는다.
  - `[오늘로 옮기기]`: `PATCH /api/actions/{id}`로 `performedDate`를 오늘로 바꾼다. 그 뒤 오늘 목록에 들어간다.
  - `[완료]`: `PUT /api/actions/{id}/completion`. 수행일은 원래 날짜 그대로다. 그날 실제로 했는데 체크를 잊은 경우에 쓴다.
- 날이 바뀌어도 서버가 수행일을 자동으로 바꾸지 않는다. 사용자가 옮기기를 눌러야 바뀐다.

#### POST `/api/actions`

```json
{
  "type": "GOAL",
  "monthlyGoalId": 8,
  "weeklyGoalId": null,
  "title": "알고리즘 문제 2개 풀기",
  "performedDate": "2026-11-12",
  "dueDate": null,
  "contributionValue": 2
}
```

- `title` 1~255자. `performedDate`는 생략하면 오늘이다. (`FN-ACT-01`)
- `type=GOAL`이면 연결 주간 목표를 다음과 같이 정한다. (`FOCUS-002`)
  - `weeklyGoalId`가 있으면 그 목표에 연결한다.
  - 없고 `monthlyGoalId`만 있으면, 그 월간 목표 아래에서 기간에 `performedDate`가 들어가는 활성 주간 목표를 찾는다. 1개면 자동으로 연결한다. 0개면 422 `WEEKLY_GOAL_REQUIRED`(수행일이 이번 주이면 화면은 `먼저 이번 주 목표를 만들어 주세요`, 지난 주이면 `그 주에 만들어 둔 주간 목표가 없어요` 안내)를 반환한다. 여러 개면 422 `WEEKLY_GOAL_SELECTION_REQUIRED`와 후보 목록을 반환한다.
  - 지난 주 날짜도 같은 방식으로 찾는다. 그 주에 만들어 둔 주간 목표가 남아 있으면 연결하고, 없으면 `WEEKLY_GOAL_REQUIRED`다. 없는 주간 목표를 새로 만들어 주지 않는다.
  - `performedDate`가 현재 주(주 시작 요일 기준, §2.3)의 마지막 날보다 뒤이면 위 판단에 앞서 422 `GOAL_ACTION_DATE_OUT_OF_RANGE`를 반환한다. 주간 목표는 현재 주로만 만들 수 있어(결정 5) 그 이후 주에는 연결할 주간 목표가 있을 수 없다. 그 주가 오면 주간 목표를 만든 뒤 추가한다. (결정 17)
  - 둘 다 없으면 400 `VALIDATION_FAILED`다. 목표 없는 핵심 행동은 만들 수 없다. (`FOCUS-009`)
- `type=MAINTENANCE`이면 `monthlyGoalId`, `weeklyGoalId`, `contributionValue`를 보내지 않는다. 수행일 제한은 없어 미래 날짜에도 추가할 수 있다.
- `contributionValue`는 연결 주간 목표가 `NUMERIC`일 때만 받으며 0 초과다.
- 체크형 주간 목표에 연결하면 분모가 늘어나므로 주간·월간 진행률을 다시 계산한다.
- 201 응답: 행동 객체
- 오류: 400 `VALIDATION_FAILED`, 422 `PARENT_GOAL_UNAVAILABLE`, 422 `WEEKLY_GOAL_REQUIRED`, 422 `WEEKLY_GOAL_SELECTION_REQUIRED`, 422 `GOAL_ACTION_DATE_OUT_OF_RANGE`, 422 `NOT_NUMERIC_GOAL`

#### PATCH `/api/actions/{id}`

- 바꿀 수 있는 필드: `title`, `performedDate`, `dueDate`, `resultSummary`, `weeklyGoalId`, `contributionValue`, `version`
- `resultSummary`는 리포트의 `기록된 결과물`이다(최대 2,000자).
- `weeklyGoalId`를 바꾸면 이전·새 경로의 진행률을 모두 다시 계산한다. `type`(목표 연결 ↔ 그 밖의 할 일)은 바꿀 수 없다.
- **완료된 행동의 수치 조정**: 같은 트랜잭션에서 수치 기록을 맞춘다. (ERD 8번 테이블 규칙)
  - `contributionValue`를 바꾸면 기록의 `amount`를 새 값으로 바꾸고 실제값에 (새 값 − 이전 값)을 더한다. 예: 실제값 2, 기여량 2 → 5로 수정하면 실제값 5. `null`로 바꾸면 기록을 지우고 이전 값을 뺀다.
  - `weeklyGoalId`를 바꾸면 이전 주간 목표에서 기여량을 빼고 새 주간 목표에 더한다. 새 목표가 체크형이면 같은 요청에서 `contributionValue`를 `null`로 보내야 한다.
- `performedDate`를 바꾸면 이전 날짜의 분류 좌표는 그대로 두고, 행동은 새 수행일의 목록에 보인다. 완료된 행동의 수행일은 미래로 바꿀 수 없다.
- 목표 연결(`GOAL`) 행동의 `performedDate`를 현재 주의 마지막 날보다 뒤로 바꿀 수 없다. 422 `GOAL_ACTION_DATE_OUT_OF_RANGE`다. `MAINTENANCE`는 제한이 없다. 지난 주 날짜로 바꾸는 것과 `[오늘로 옮기기]`는 막지 않는다. (결정 17)
- 오류: 400 `VALIDATION_FAILED`, 409 `VERSION_CONFLICT`, 422 `PARENT_GOAL_UNAVAILABLE`, 422 `NOT_NUMERIC_GOAL`, 422 `FUTURE_ACTION_COMPLETION_BLOCKED`, 422 `GOAL_ACTION_DATE_OUT_OF_RANGE`

#### PUT `/api/actions/{id}/completion`

완료 상태를 지정한다. 같은 요청을 반복하거나 여러 인스턴스에 동시에 도착해도 한 번만 반영한다. 서로 다른 행동이 같은 주간 목표에 동시에 반영되어도 기여량이 빠지지 않는다. (`FN-ACT-02`, ERD 결정 16)

```json
{ "completed": true, "memo": "2문제 풀이 완료" }
```

- 이미 같은 상태면 아무것도 바꾸지 않고 200을 반환한다.
- 수행일이 오늘보다 뒤인 행동은 완료할 수 없다. 아직 하지 않은 일이 리포트에 집계되지 않게 하기 위해서다. 완료 취소는 언제든 할 수 있다. (`DASH-011`)
- **완료**: `is_completed=true`, `completed_at` 기록. 수치형 주간 목표이고 `contributionValue`가 있으면 `weekly_goal_numeric_records`에 `ACTION_COMPLETION` 행을 넣고 `current_value`를 더한다. `action_id` UNIQUE 제약은 같은 행동의 중복 가산을 막는다.
- **완료 취소**: 해당 수치 기록을 지우고 `current_value`에서 뺀다.
- **동시 처리**: `current_value`는 Java에서 읽어 더하지 않고 `UPDATE weekly_goals SET current_value = current_value + :delta WHERE id = :id`로 DB에서 바꾼다. 서로 다른 행동 두 개가 다른 인스턴스에서 동시에 완료돼도 두 변경이 모두 반영된다. 진행률은 같은 트랜잭션에서 `SELECT ... FOR UPDATE`로 주간 목표 → 월간 목표 순서로 행을 잠근 뒤 값을 다시 읽어 계산한다. 이 순서를 고정해 교착 상태를 막는다. 기록 삽입·삭제도 같은 트랜잭션이다. 이 경로는 `version`을 올리지 않는다.
- 목표 연결 행동이면 주간·월간 진행률을 다시 계산하고 바뀐 값을 `goal_progress_logs`에 남긴다. `memo`는 그 기록의 메모로 쓴다(최대 255자, 생략하면 서버가 `행동 완료: {제목}` 형식으로 채운다).
- `MAINTENANCE`는 자신의 완료 상태만 바꾼다. (`FOCUS-006`, `FOCUS-011`)
- 연간 달성 기준은 바꾸지 않는다.
- 응답:

```json
{
  "action": { /* 행동 객체 */ },
  "weeklyGoal": { "id": 31, "progress": { "state": "RECORDED", "rate": 100.00 }, "currentValue": 14.00, "isTargetReached": true },
  "monthlyGoal": { "id": 8, "progress": { "state": "RECORDED", "rate": 66.67 } }
}
```

`MAINTENANCE`이면 `weeklyGoal`, `monthlyGoal`이 `null`이다.

#### DELETE `/api/actions/{id}`

행동을 소프트 삭제해 목록에서 숨긴다. 행동 단위 휴지통·복원은 제공하지 않는다.

- **미완료 행동**: 체크형 진행률의 분모에서 빠지므로 주간·월간 진행률을 다시 계산한다.
- **완료된 행동**: 목록에서만 숨긴다. 완료 기록은 남기기로 했으므로(ERD §4.4) 리포트 집계, 수치 기록, 체크형 진행률에 그대로 남는다. 기록까지 완전히 없애려면 완료를 취소한 뒤 삭제한다.
- 오류: 404 `RESOURCE_NOT_FOUND`

### 4.10 사분면 분류

분류는 오늘 수행일인 행동에만 한다. 점을 놓거나 옮기거나 트레이로 되돌리는 요청은 하나의 API(`PUT .../placement`)이며 미확정 변경만 만든다. `[오늘 분류 확정]`을 눌러야 확정 좌표가 바뀐다. 이미 확정한 점을 옮기거나 트레이로 되돌린 뒤 확정하지 않으면 이전 확정 좌표가 그대로 남는다. (`FN-QUAD-02`, ERD 9번 테이블)

예: 아침에 "보고서 쓰기"를 (80, 90)으로 확정했다. 오후에 (60, 30)으로 옮기고 확정하지 않았다. 이 상태는 `RECLASSIFYING`이며 확정 좌표는 (80, 90), 화면의 점은 (60, 30)이다. `[변경 취소]`를 누르면 점이 (80, 90)으로 돌아간다.

날이 지나도록 확정하지 못한 변경은 그 날짜에 대해 확인·취소·확정할 방법이 없다(분류는 오늘만 가능). 그래서 오늘이 아닌 날짜를 조회할 때는 미확정 좌표와 되돌리기 대기를 응답에 반영하지 않고 확정 좌표만 쓴다. DB의 해당 값은 지우지 않는다. (결정 18)

#### PUT `/api/actions/{id}/placement`

점을 사분면에 놓거나 옮기거나 트레이로 되돌린다. 행동의 미확정 위치를 지정하는 하나의 요청이며, 미확정 좌표만 저장한다. (`FN-QUAD-01`, 결정 19)

```json
{ "importance": 72, "urgency": 70 }
```

트레이로 되돌릴 때는 두 값을 모두 `null`로 보낸다. 트레이는 좌표가 없는 위치다.

```json
{ "importance": null, "urgency": null }
```

- 두 필드는 항상 함께 보낸다. 둘 다 0~100의 정수이거나 둘 다 `null`이어야 한다. 하나만 `null`이거나 필드가 빠지면 400 `VALIDATION_FAILED`다.
- **좌표를 보낼 때**: `GOAL` 행동은 `importance >= 50`이어야 한다. `MAINTENANCE`는 제한이 없다. (`FOCUS-016`) 되돌리기 대기 상태였다면 그 대기를 풀고 새 미확정 좌표를 둔다.
- **`null`을 보낼 때**: 확정 좌표가 없으면(미분류·미확정) 행을 지워 바로 `UNCLASSIFIED`가 된다. 확정 좌표가 있으면 `PENDING_UNCLASSIFY`가 된다. 확정을 눌러야 미분류가 되고, 그 전까지 확정 좌표는 남는다. 이미 트레이에 있는 점이면 아무것도 바꾸지 않고 200이다.
- 좌표는 완료 상태, 진행률, 저장 순서를 바꾸지 않는다.
- 응답: `{ "actionId": 101, "placement": { /* §4.9 placement */ } }`
- 오류: 400 `VALIDATION_FAILED`, 404 `RESOURCE_NOT_FOUND`, 422 `QUADRANT_OUT_OF_RANGE`, 422 `CLASSIFICATION_DATE_INVALID`

#### DELETE `/api/actions/{id}/placement/pending`

확정하지 않은 변경을 취소한다. 미확정 좌표와 되돌리기 대기를 지우고 확정 좌표 상태로 돌아간다. 확정 좌표가 없던 행동은 미분류가 된다. 변경이 없어도 200이다.

#### POST `/api/actions/placements/confirm`

오늘 날짜의 확정하지 않은 변경을 한 트랜잭션에서 모두 확정한다. (`FN-QUAD-02`)

- 미확정 좌표가 있는 행: 미확정 좌표를 확정 좌표로 옮기고 `confirmed_at`을 갱신한다.
- 되돌리기 대기 행: 행을 지워 미분류로 만든다.
- 트레이에 있던 미분류 점은 그대로 미분류로 남는다.
- 응답: `{ "date": "2026-11-12", "confirmedCount": 3, "unclassifiedCount": 1 }`
- 확정할 변경이 없어도 200이며 `confirmedCount`는 0이다.

### 4.11 달력

달력 항목 객체:

```json
{
  "id": 70,
  "itemType": "EVENT",
  "title": "팀 프로젝트 발표",
  "description": null,
  "startDate": "2026-11-13",
  "endDate": "2026-11-13",
  "isCompleted": false,
  "completedAt": null,
  "yearlyGoal": { "id": 3, "title": "백엔드 개발자 취업 준비", "color": "#3B82F6" },
  "daysUntilDue": 1,
  "version": 0
}
```

- `itemType`: `TASK`(해야 할 일) 또는 `EVENT`(일정). `TASK`는 `startDate`와 `endDate`가 모두 마감일이다.
- `yearlyGoal`이 `null`이면 목표에 연결하지 않은 항목이다. 연결된 목표가 소프트 삭제 상태면 응답의 `yearlyGoal`은 `null`로 보인다.
- `daysUntilDue`: `TASK`는 마감일까지, `EVENT`는 시작일까지 남은 일수다.

#### GET `/api/calendar/items?from=2026-11-01&to=2026-12-05&yearlyGoalIds=3,5&includeUnlinked=true`

`[from, to]`와 하루라도 겹치는 항목을 `startDate`, `id` 순으로 반환한다. (`FN-CAL-01`)

- `from`, `to`는 필수이며 범위는 최대 100일이다. 월 달력의 앞뒤 주까지 한 번에 받는다.
- `yearlyGoalIds`를 생략하면 모든 목표의 항목을 준다. `includeUnlinked`(기본 `true`)는 목표에 연결하지 않은 항목을 포함할지다. 둘을 조합해 목표별 필터를 만든다. (`DASH-007`)
- 오류: 400 `VALIDATION_FAILED`, 422 `INVALID_PERIOD`

#### POST `/api/calendar/items`

```json
{ "itemType": "TASK", "title": "과제 제출", "description": null, "startDate": "2026-11-14", "endDate": null, "yearlyGoalId": 3 }
```

- `TASK`는 `endDate`를 보내지 않거나 `startDate`와 같게 보낸다. `EVENT`는 `endDate`를 생략하면 하루 일정이며, 있으면 `startDate` 이상이어야 한다.
- `yearlyGoalId`는 선택이며 활성 연간 목표여야 한다.
- 201 응답: 달력 항목 객체
- 오류: 400 `VALIDATION_FAILED`, 422 `PARENT_GOAL_UNAVAILABLE`

#### PATCH `/api/calendar/items/{id}`

바꿀 수 있는 필드는 `title`, `description`, `startDate`, `endDate`, `yearlyGoalId`, `version`이다. `itemType`은 바꿀 수 없다.

- 오류: 400 `VALIDATION_FAILED`, 409 `VERSION_CONFLICT`, 422 `ITEM_TYPE_IMMUTABLE`, 422 `PARENT_GOAL_UNAVAILABLE`

#### PUT `/api/calendar/items/{id}/completion`

`{ "completed": true }`. 같은 상태면 그대로 200이다. 행동의 수행일·목표 진행률을 만들거나 바꾸지 않는다. (`DASH-010`)

#### DELETE `/api/calendar/items/{id}`

휴지통 없이 즉시 영구 삭제한다. 확인 창은 프론트엔드가 띄운다. (ERD 결정 8)

#### GET `/api/calendar/dashboard`

대시보드의 `해야 할 일`·`일정` 두 열을 서버가 계정 시간대로 골라 준다. (`DASH-001`)

```json
{
  "today": "2026-11-12",
  "tasks": [ /* 미완료 TASK 전체. 마감일, id 순 */ ],
  "events": [ /* 오늘~7일 이내에 시작하거나 오늘 진행 중인 EVENT. 시작일 순 */ ],
  "pastEvents": [ /* 종료일이 최근 7일 이내인 EVENT. 각 항목에 "daysSinceEnd": 1 추가 */ ]
}
```

- `tasks`: 기한과 관계없이 미완료 `TASK` 전체. 완료하면 목록에서 빠진다.
- `events`: `startDate <= today + 7`이고 `endDate >= today`인 `EVENT`. 완료 여부와 관계없이 포함하고 완료 항목은 화면에서 옅게 표시한다.
- `pastEvents`: `today - 7 <= endDate < today`인 `EVENT`. 화면은 `D+{daysSinceEnd}`로 표시한다.

### 4.12 리포트와 직접 회고

#### GET `/api/reports/tabs?month=2026-12`

기준 월에 끝나는 리포트 기간을 탭 순서(월간 → 분기 → 연간)로 반환한다. `month`를 생략하면 이번 달이다. 미래 달은 422 `INVALID_PERIOD`다. (`REVIEW-004`)

```json
{
  "month": "2026-12",
  "tabs": [
    { "periodType": "MONTHLY", "period": "2026-12", "label": "2026.12 월간", "startDate": "2026-12-01", "endDate": "2026-12-31" },
    { "periodType": "QUARTERLY", "period": "2026-Q4", "label": "2026 4분기", "startDate": "2026-10-01", "endDate": "2026-12-31" },
    { "periodType": "YEARLY", "period": "2026", "label": "2026년 연간", "startDate": "2026-01-01", "endDate": "2026-12-31" }
  ]
}
```

월간 탭은 항상 있다. 분기 탭은 3·6·9·12월, 연간 탭은 12월에만 있다. 프론트엔드는 첫 탭(월간)을 바로 조회하고, 다른 탭은 사용자가 누를 때 `GET /api/reports`로 조회한다.

#### GET `/api/reports?periodType=MONTHLY&period=2026-11`

| `periodType` | `period` 형식 | 기간 |
|---|---|---|
| `MONTHLY` | `2026-11` | 그 달 1일~말일 |
| `QUARTERLY` | `2026-Q4` | 달력 분기 |
| `YEARLY` | `2026` | 1월 1일~12월 31일 |

진행 중인 기간도 조회할 수 있으며 오늘까지의 기록으로 계산한다. 미래 기간은 422 `INVALID_PERIOD`다.

```json
{
  "periodType": "MONTHLY",
  "period": "2026-11",
  "startDate": "2026-11-01",
  "endDate": "2026-11-30",
  "summary": { "completedActionCount": 12, "activeDayCount": 8 },
  "actions": [
    { "id": 101, "title": "알고리즘 문제 2개 풀기", "performedDate": "2026-11-12", "goalPath": { /* 당시 목표 이름 */ }, "resultSummary": "풀이 블로그 정리" }
  ],
  "results": [
    { "actionId": 101, "performedDate": "2026-11-12", "resultSummary": "풀이 블로그 정리" }
  ],
  "goalProgress": [
    { "yearlyGoalId": 3, "title": "백엔드 개발자 취업 준비", "color": "#3B82F6", "state": "COMPARABLE", "startBase": "PREVIOUS_PERIOD_END", "startLabel": "10월 말", "endLabel": "11월 말", "startRate": 20, "endRate": 35, "changePp": 15, "observedFrom": "2026-11-01", "note": null },
    { "yearlyGoalId": 4, "title": "영어 회화", "color": "#F59E0B", "state": "COMPARABLE", "startBase": "INITIAL_BASELINE", "startLabel": "기록 시작 (11.05)", "endLabel": "11월 말", "startRate": 10, "endRate": 30, "changePp": 20, "observedFrom": "2026-11-05", "note": "11.05 기준 확정 이후 변화" },
    { "yearlyGoalId": 5, "title": "건강 관리", "color": "#10B981", "state": "NOT_CONFIGURED", "startBase": null, "startLabel": null, "endLabel": null, "startRate": null, "endRate": null, "changePp": null, "observedFrom": null, "note": "달성 기준 설정 전" }
  ],
  "review": { "improvements": "...", "nextFocus": "...", "version": 1 }
}
```

- **집계 대상** (`FN-REPORT-01`): 수행일이 기간 안이고 완료한 `GOAL` 행동. 소프트 삭제나 과거 이력이 된 목표에 연결된 완료 행동도 포함한다. `MAINTENANCE`는 제외한다. 활동일은 그런 행동이 하나 이상 있는 날짜 수다. 기록이 없으면 0과 빈 배열을 그대로 준다. 추정하지 않는다.
- `results`가 비면 화면은 `기록된 결과물 없음`을 표시한다.
- **목표별 달성률 변화** (`FN-REPORT-02`, ERD §4.2): 기간이 속한 연도의 연간 목표 중 영구 삭제되지 않은 것을 대상으로 한다.

| `state` | 조건 | 값 |
|---|---|---|
| `NOT_CONFIGURED` | 기간 말(또는 오늘)까지 기준을 확정하지 않음 | 숫자는 모두 `null` |
| `CRITERIA_REVISED` | 기간 안에 `CRITERIA_REVISION` 기록이 있음 | `changePp`는 `null`, `note`에 `기준·배점 변경으로 비교 불가` |
| `COMPARABLE` | 그 밖의 경우 | `startRate`는 기간 시작 전 마지막 관측값. 기간 중 최초 확정이면 최초 기준선이고 `observedFrom`이 확정일이다. `endRate`는 기간 말 마지막 관측값 |

- **세로 막대 비교** (`REVIEW-004`): 화면은 `COMPARABLE` 목표마다 `startRate`(왼쪽)와 `endRate`(오른쪽) 막대 두 개를 그리고 아래에 `startLabel`·`endLabel`과 `changePp`를 적는다.
  - `startBase=PREVIOUS_PERIOD_END`: 왼쪽 막대는 지난 기간 말의 값이다. `startLabel`은 `10월 말`, `3분기 말`, `2025년 말`처럼 지난 기간 이름으로 만든다.
  - `startBase=INITIAL_BASELINE`: 기간 중에 기준을 처음 확정했다. 왼쪽 막대는 최초 기준선이고 `startLabel`은 `기록 시작 (MM.DD)`이다.
  - `endLabel`은 이번 기간 이름(`11월 말`, `4분기 말`, `2026년 말`)이다. 진행 중인 기간이면 `오늘 (MM.DD)`이다.
  - `NOT_CONFIGURED`, `CRITERIA_REVISED`는 막대 없이 `note`를 표시한다.
- `review`는 그 기간의 직접 회고이며 없으면 `null`이다. 탭마다 따로 저장한다.

#### PUT `/api/reports/reviews/{periodType}/{period}`

직접 회고를 저장한다. 없으면 만들고 있으면 고친다. (`FN-REPORT-03`)

```json
{ "improvements": "하루 2문제 꾸준히 풀기", "nextFocus": "그래프 알고리즘", "version": 1 }
```

- 두 필드 모두 선택이며 각각 최대 5,000자다. 처음 저장할 때는 `version`을 생략한다.
- 서버는 사용자가 쓴 내용만 저장한다. AI 문장을 넣지 않는다.
- 오류: 400 `VALIDATION_FAILED`, 409 `VERSION_CONFLICT`, 422 `INVALID_PERIOD`

## 5. 화면별 호출 API

| 화면 | 진입 시 호출 | 주요 동작 |
|---|---|---|
| 공통 상단 내비게이션 | `GET /api/users/me` | 로그아웃 `POST /api/auth/logout` |
| 로그인·회원가입 | `GET /api/auth/csrf`(`XSRF-TOKEN` 쿠키가 없을 때) | `POST /api/auth/login`, `POST /api/auth/signup`, `POST /api/auth/verification-emails` |
| 이메일 인증 결과 | `GET /api/auth/csrf`(`XSRF-TOKEN` 쿠키가 없을 때) | `[인증 완료]` 버튼 → `POST /api/auth/email-verification` |
| 비밀번호 재설정 | `GET /api/auth/csrf`(`XSRF-TOKEN` 쿠키가 없을 때) | `POST /api/auth/password-reset-requests`, `POST /api/auth/password-reset` |
| 온보딩 | `GET /api/goals/onboarding/status`, `GET /api/goals/onboarding/draft`, `GET /api/settings`, `GET /api/goals/yearly` | 단계 이동마다 `PUT .../draft`, 완료 `POST .../complete` |
| 대시보드 | `GET /api/settings`, `GET /api/actions`, `GET /api/calendar/dashboard`, `GET /api/goals/weekly`, `GET /api/goals/monthly`, `GET /api/goals/progress-logs` | 행동 완료, 달력 항목 완료, 날짜 이동 시 `GET /api/actions?date=` 재호출 |
| 월간·주간 목표 설정 | `GET /api/goals/yearly`, `GET /api/goals/monthly`, `GET /api/goals/weekly/previous-summary` | 월간·주간 생성·수정·삭제 |
| 대시보드 오늘 행동 영역(분류 포함) | `GET /api/actions` | 행동 추가·완료, 배치·되돌리기·확정 |
| 달력 | `GET /api/calendar/items`, `GET /api/goals/yearly` | 항목 생성·수정·완료·삭제 |
| 연간 목표 | `GET /api/goals/yearly` | 생성, 순서 저장, 수정, 삭제 |
| 연간 목표 상세 | `GET /api/goals/yearly/{id}`, `GET .../criteria` | 기준 저장·확정·달성, 상태 변경 |
| 삭제된 목표 | `GET /api/goals/trash` | 복원 |
| 리포트 | `GET /api/reports/tabs`, 첫 탭의 `GET /api/reports` | 기준 월 이동 시 탭 재조회, 탭 전환 시 해당 기간 `GET /api/reports`, 직접 회고 저장 |
| 설정 | `GET /api/users/me`, `GET /api/settings` | `PUT /api/settings` |

대시보드는 여러 API를 나눠 호출한다. 각 요청이 RR로 다른 `goal-service` 인스턴스에 가므로, 한 화면을 여는 것만으로도 `X-Instance-Id`가 바뀌는 것을 시연할 수 있다.

## 6. 결정 사항

### 결정 1 — 성공·오류 응답에 같은 `code`·`message` 구조
성공은 `{code: "SUCCESS", message, data}`, 오류는 `{code, message, details}`다. 프론트엔드는 응답마다 `code`를 먼저 확인하는 한 가지 방식으로 처리한다. 성공 코드는 기능마다 나누지 않는다. 기능별 의미는 HTTP 메서드와 경로로 충분히 구분되기 때문이다.

### 결정 2 — `version`으로 동시 수정 방지 (`PROG-008`)
요구사항에서 권장 항목이지만 RR 분산 환경에서 같은 사용자의 요청이 서로 다른 인스턴스에 동시에 도착할 수 있어 적용한다. ERD의 수정 가능 테이블에 `version` 컬럼을 추가한다. 완료·달성처럼 상태를 지정하는 요청은 여러 번 보내도 결과가 같으므로 `version`을 요구하지 않는다.

### 결정 3 — 온보딩 API는 `/api/goals/onboarding` 아래에 둔다
프록시 라우팅을 새로 추가하지 않고 기존 `/api/goals` 경로로 `goal-service`에 보낸다. 완료 요청 한 번으로 모든 목표·행동을 한 트랜잭션에서 만들어, 이탈 시 일부만 저장되는 상태를 막는다.

### 결정 4 — 비밀번호 규칙과 토큰 만료
비밀번호는 8~64자이며 영문·숫자·특수문자를 각각 포함한다. 인증 링크는 24시간, 재설정 링크는 30분 뒤 만료된다. 재설정 성공 시 해당 사용자의 기존 세션을 모두 끊는다.

### 결정 5 — 주간 목표는 현재 주로만 생성
월간 목표가 현재 달로만 만들어지는 규칙(`GOAL-014`)과 맞춰 주간 목표도 이번 주로만 만든다. 계획 흐름(주초에 이번 주 계획)과 화면(`이번 주 목표 만들기`)에도 맞는다.

### 결정 6 — 연결 주간 목표 자동 선택을 서버가 판단
행동 추가 시 `monthlyGoalId`만 보내면 서버가 연결 주간 목표를 0개·1개·여러 개로 판단한다(`FOCUS-002`). 브라우저의 목록과 서버의 실제 상태가 달라도 규칙이 한 곳에서 지켜진다.

### 결정 7 — 완료된 행동의 수정과 삭제
완료된 행동도 기여량과 연결 주간 목표를 고칠 수 있다. 서버가 같은 트랜잭션에서 차이만큼 실제값을 조정한다. 완료된 행동을 삭제하면 목록에서만 숨기고 완료 기록은 리포트와 진행률에 남긴다. 목표를 삭제해도 완료 기록은 남긴다는 규칙(ERD §4.4)과 같다. 기록까지 없애려면 완료를 취소한 뒤 삭제한다. 직접 입력한 수치 기록은 잘못 입력을 고칠 수 있도록 삭제를 허용한다.

### 결정 8 — 다른 사용자의 리소스는 404
403을 쓰면 리소스가 있다는 사실이 드러난다. 소유자가 아니면 없는 리소스와 같게 404로 응답한다.

### 결정 9 — 확정 좌표는 다시 확정할 때까지 유지
기능 명세 FN-QUAD-02대로, 확정한 점을 옮기거나 트레이로 되돌려도 다시 확정하기 전에는 이전 확정 좌표를 바꾸지 않는다. 이를 위해 ERD 9번 테이블에 미확정 좌표 컬럼과 되돌리기 대기 컬럼을 추가했다.

### 결정 10 — 지난 미완료 행동은 따로 보여주고 사용자가 옮긴다 (`FOCUS-017`)
수행일이 지난 미완료 행동은 오늘 목록 아래 `지난 미완료` 영역에 보여준다. 수행일을 자동으로 오늘로 바꾸지 않는다. 리포트는 실제 수행일로 집계하므로, 사용자가 `[오늘로 옮기기]`(오늘 할 일)와 `[완료]`(그날 이미 했음) 중 직접 고르게 한다.

### 결정 11 — 대시보드 날짜 이동은 오늘 행동 영역에만 (`DASH-011`)
`해야 할 일`은 날짜와 무관한 미완료 전체이고, 일정·이번 주·이번 달은 지금 기준으로 계획을 세우는 영역이다. 그래서 날짜 이동은 오늘 행동 영역에만 적용하고 새 API 없이 `GET /api/actions?date=`를 쓴다. 미래 날짜 행동의 완료를 서버에서 막아, 아직 하지 않은 일이 리포트에 들어가지 않게 한다.

### 결정 12 — 리포트는 기준 월에 끝나는 기간을 탭으로 (`REVIEW-004`)
3월·6월·9월에는 월간과 분기, 12월에는 월간·분기·연간 리포트가 함께 끝난다. 기준 월 하나를 고르면 그 달에 끝나는 리포트를 모두 탭으로 보여준다. 탭 목록은 서버(`/api/reports/tabs`)가 정해 분기·연말 판단을 한 곳에 둔다. 연간 목표 달성률 변화는 표 대신 지난 기간 말과 이번 기간 말의 세로 막대 두 개로 보여주고, 숫자와 %p를 함께 적는다. 비교 기준(지난 기간 말 또는 최초 기준선)은 `startBase`로 구분해, 기간 중에 기준을 처음 확정한 목표의 변화가 기간 전체의 성과로 보이지 않게 한다.

### 결정 13 — CSRF는 Spring Security 토큰으로 막는다 (`NFR-009`)
**문제.** CSRF는 로그인한 사용자가 악성 사이트를 열었을 때, 그 사이트의 코드가 우리 API로 요청을 보내게 하는 공격이다. 브라우저는 요청 대상이 우리 서버이면 `SESSION` 쿠키를 자동으로 붙이므로, 서버는 정상 요청과 구별하지 못한다. 예를 들어 사용자가 Aim2Day에 로그인한 채 악성 페이지를 열면, 그 페이지의 폼이 `DELETE /api/goals/yearly/3?confirm=true`나 `PUT /api/settings`를 보내 목표를 지우거나 설정을 바꿀 수 있다. 이전 문서는 "`SameSite=Lax`와 JSON 전용 요청으로 막는다"고 적었지만 너무 강한 주장이었다. `SameSite`는 같은 사이트(같은 등록 도메인의 다른 하위 도메인)에서 온 요청을 교차 사이트로 보지 않으므로, 형제 하위 도메인의 페이지가 침해되면 쿠키가 그대로 붙는다. 브라우저별 구현 차이와 구형 브라우저도 변수다. Spring Security 문서도 `SameSite`를 심층 방어의 하나로 설명하며 CSRF 토큰을 대체하지 않는다. `Content-Type` 제한도 브라우저·프록시 설정이 바뀌면 깨질 수 있는 간접 방어다.

**결정.** `user-service`와 `goal-service` 모두 상태 변경 요청(POST·PUT·PATCH·DELETE) 전부에 Spring Security CSRF 보호를 켠다. `CookieCsrfTokenRepository.withHttpOnlyFalse()`로 JS가 읽을 수 있는 `XSRF-TOKEN` 쿠키를 내려주고, 프론트엔드가 그 값을 `X-XSRF-TOKEN` 헤더로 보내 서버가 비교한다. 악성 사이트는 우리 쿠키를 읽을 수 없어 헤더 값을 만들 수 없다. 토큰을 서버에 저장하지 않으므로 RR로 어느 `goal-service` 인스턴스가 받아도 검증된다. 로그인·가입 같은 `/api/auth` POST도 포함한다. 공격자가 자기 계정으로 피해자를 로그인시키는 로그인 CSRF를 막기 위해서다. `SameSite=Lax`는 추가 방어로 유지한다.

로그인 전 화면도 토큰이 필요하므로 인증이 필요 없는 `GET /api/auth/csrf`를 추가했다. `GET /api/users/me`는 로그인 후에만 호출할 수 있어 로그인 화면에 쓸 수 없다. 이 엔드포인트는 쿠키를 내려주는 일만 한다.

**버린 대안.** (1) `SameSite=Strict`만 사용: 외부 링크로 들어올 때 첫 요청에 쿠키가 붙지 않아 로그인이 풀린 것처럼 보이고, 같은 사이트 공격은 여전히 막지 못한다. (2) `Origin` 헤더 검사만 사용: 프록시가 헤더를 바꾸거나 일부 요청에 헤더가 없으면 우회·오탐이 생긴다. 보조 수단으로는 가능하다. (3) 세션에 토큰 저장(`HttpSessionCsrfTokenRepository`): Spring Session으로 공유는 되지만 쿠키 방식이 SPA와 맞고 서버 상태가 없어 쿠키 방식을 택했다.

### 결정 14 — 비밀번호 재설정 뒤 전체 로그아웃을 위해 인덱스형 세션 저장소를 쓴다 (`AUTH-008`)
**문제.** Spring Session 3부터 기본인 `RedisSessionRepository`는 세션 ID로만 세션을 찾는다. 예를 들어 사용자 7이 노트북과 휴대폰에서 로그인해 세션이 두 개 있을 때, 비밀번호를 재설정해도 서버는 "사용자 7의 세션이 무엇인가"를 조회할 수 없어 두 세션을 지울 수 없다. 이전 문서의 "사용자 이름 인덱스 사용"은 이 기본 저장소로는 동작하지 않는다. 비밀번호가 유출돼 재설정한 사용자의 공격자 세션이 계속 살아 있게 된다.

**결정.** `user-service`와 `goal-service` 모두 `@EnableRedisIndexedHttpSession`(`RedisIndexedSessionRepository`)을 쓴다. 로그인할 때 `userId`를 문자열로 세션 속성 `FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME`에 저장하고, 재설정 때 `findByPrincipalName(userId)`로 찾은 세션을 모두 삭제한다. 저장소 종류가 다르면 Redis 키 구조가 달라 서비스끼리 세션을 읽지 못하므로 `goal-service` 인스턴스도 같은 설정을 쓴다. 인덱스형 저장소는 만료 이벤트를 받으려고 Redis 키 공간 알림이 필요하다. Spring Session이 기본으로 자동 설정하고, 이 프로젝트의 Redis는 자체 호스팅 컨테이너라 그대로 동작한다. 관리형 Redis는 설정 명령이 막혀 있을 수 있어 `notify-keyspace-events`를 직접 켜야 한다.

**버린 대안.** (1) 사용자별 세션 ID 목록을 별도 Redis 키로 직접 관리: 세션 만료·삭제와 목록을 맞추는 코드를 직접 짜야 한다. (2) 세션에 비밀번호 변경 시각을 넣고 매 요청에서 비교: 요청마다 DB 조회가 늘고 모든 서비스에 같은 검사를 넣어야 한다.

### 결정 15 — 메일 재발송·재설정 API는 메일 실패를 응답에 드러내지 않는다 (`AUTH-007`, `AUTH-008`)
**문제.** `POST /api/auth/verification-emails`와 `POST /api/auth/password-reset-requests`는 가입 여부와 상관없이 같은 200을 주기로 했다. 그런데 메일 전송 실패 때 502를 주면, 메일 서버가 불안정한 동안에는 "실제 계정이 있는 이메일에만 502, 없는 이메일에는 200"이 되어 계정 존재 여부가 드러난다. 예를 들어 공격자가 `a@x.com`과 `b@x.com`을 보냈는데 앞의 것만 502면 `a@x.com`이 가입자임을 알 수 있다.

**결정.** 두 API는 항상 200 `data: null`을 반환한다. 메일 전송 실패는 서버 로그에만 남긴다(`NFR-010`에 따라 토큰 원문과 이메일은 남기지 않고 사용자 ID만 기록). 응답에서는 502를 제거했다. 사용자는 메일이 오지 않으면 60초 재요청 제한이 풀린 뒤 다시 요청할 수 있다. 회원가입의 502 `MAIL_SEND_FAILED`는 유지한다. 가입은 이미 409 `EMAIL_ALREADY_EXISTS`로 가입 여부를 드러내고, 가입 직후에는 사용자가 인증 메일을 다시 요청해야 한다는 사실을 알아야 하기 때문이다.

**버린 대안.** (1) 존재하지 않는 계정에도 같은 확률로 502를 주는 방법: 실제로 보내지 않은 요청에 가짜 실패를 만들어야 하고, 정상 사용자에게 불필요한 실패를 보여준다. (2) 실패한 메일을 큐에 쌓아 자동 재시도: 제출판 범위를 넘고, 재시도 중에도 사용자에게 상태를 알릴 방법이 필요하다.

### 결정 16 — 진행률 동시 갱신과 `version` 분리 (ERD 결정 16·17 참조)
서로 다른 행동의 동시 완료에서 기여량이 사라지지 않게 하고, 진행률 변경이 목표 수정 화면의 `version`과 충돌하지 않게 하는 방법은 DB 구조와 직결되어 ERD 결정 16·17에 상세히 적었다. API에서는 §2.4와 §4.6·§4.9의 규칙을 따른다. 요약하면 `current_value`는 원자적 UPDATE로, 진행률 재계산은 잠금 순서(주간 → 월간)를 고정한 같은 트랜잭션에서 처리하고, 이 컬럼들은 엔티티에서 읽기 전용이라 `version`을 올리지 않는다.

### 결정 17 — 목표 연결 행동은 현재 주 안의 날짜까지만 추가한다 (`FOCUS-002`, `DASH-011`)
**문제.** 대시보드는 미래 날짜에도 행동을 미리 추가하게 한다. 그런데 목표 연결 행동은 주간 목표 아래에 저장되고, 주간 목표는 현재 주로만 만들 수 있다(결정 5). 예를 들어 오늘이 11월 12일(목)이고 이번 주가 11월 8~14일일 때 11월 20일 행동을 월간 목표에 연결하려 하면 그 주에는 주간 목표가 없다. 서버는 `WEEKLY_GOAL_REQUIRED`를 주겠지만 화면이 안내하는 `먼저 이번 주 목표를 만들어 주세요`는 이번 주가 아닌 날짜에는 틀린 말이다. 이번 주 목표를 만들어도 그 날짜의 문제는 풀리지 않는다.

**결정.** 목표 연결 행동의 `performedDate`는 현재 주의 마지막 날 이전이어야 한다. 현재 주 이후 날짜에서는 `그 밖의 할 일`(`MAINTENANCE`)만 추가할 수 있고, 목표 행동은 그 주가 되어 주간 목표를 만든 뒤 추가한다. 이를 어기면 `POST /api/actions`와 `PATCH /api/actions/{id}`가 422 `GOAL_ACTION_DATE_OUT_OF_RANGE`를 반환한다. 화면은 같은 날짜에서 폼의 목표 선택을 막고 안내 문구를 보여 주므로 이 오류는 방어용이다. 지난 주 날짜에는 새 규칙을 두지 않고 기존 방식으로 그 날짜를 기간에 포함하는 주간 목표를 찾는다. 그 주에 만든 주간 목표가 있으면 연결하고 없으면 `WEEKLY_GOAL_REQUIRED`다. 이때 응답 `details`에 `performedDate`를 넣어 화면이 이번 주와 지난 주 문구를 가르게 했다. `[오늘로 옮기기]`처럼 지난 주 연결을 그대로 둔 채 수행일만 오늘로 바꾸는 요청은 현재 주 안이므로 막지 않는다.

**버린 대안.** (1) 미래 주간 목표를 미리 만들 수 있게 한다: 결정 5와 월간 목표의 현재 달 규칙을 깨고, 주간 목표 기간 겹침·리포트 기간 계산·`이번 주` 판단이 복잡해진다. (2) `WEEKLY_GOAL_REQUIRED`를 그대로 쓴다: 미래 주에서는 사용자가 할 수 있는 조치가 없어 안내가 틀린다. 오류 원인(날짜가 범위 밖)이 코드에 드러나는 편이 낫다. (3) 미래 날짜 행동을 아예 막는다: `그 밖의 할 일`은 주간 목표가 필요 없고 미리 적어 두는 쓰임이 있어(`DASH-011`) 유지한다.

### 결정 18 — 지난 날짜의 확정하지 않은 변경은 없는 것으로 본다 (`FOCUS-016`, `FN-QUAD-02`)
**문제.** 분류는 수행일이 오늘인 행동에만 할 수 있다(`CLASSIFICATION_DATE_INVALID`). 그래서 어제 점을 옮기고 확정하지 않은 채 날이 지나면, 그 변경은 확정할 수도 취소할 수도 없다. 그대로 두면 어제 날짜를 조회할 때 확정하지 않은 위치가 확정 좌표처럼 보이거나, `RECLASSIFYING`·`PENDING_UNCLASSIFY` 상태가 영원히 남는다.

**결정.** 오늘이 아닌 날짜를 조회할 때는 확정 좌표만 쓰고, 미확정 좌표와 되돌리기 대기는 없는 것으로 응답한다. 확정 좌표가 있으면 `CONFIRMED`, 없으면 `UNCLASSIFIED`다. `classification.unconfirmedChanges`도 0이다. DB의 미확정 값은 지우지 않는다. 읽을 때만 무시하면 요청 경로에 쓰기가 생기지 않고, 별도 정리 코드가 필요 없으며, 규칙이 읽는 한 곳에만 있어 인스턴스마다 결과가 같다. 기존 예약 작업은 30일 지난 삭제 목표를 정리하는 작업(ERD §4.4)이라 사분면 값을 지우는 일과 성격이 맞지 않는다. 남은 값은 어디에서도 쓰이지 않으므로 저장 공간 외의 문제가 없다.

**버린 대안.** (1) 자정에 예약 작업으로 지난 날짜의 미확정 값을 지운다: 시간대별 자정 판단이 필요하고 작업이 실패하면 읽기 결과가 달라진다. 읽을 때 무시하는 규칙은 어차피 필요해 이중이 된다. (2) 지난 날짜도 확정·취소를 허용한다: `오늘 분류`라는 기능 정의와 맞지 않고 지난 기록이 소급해서 바뀐다. (3) 날이 바뀔 때 미확정 변경을 자동 확정한다: 사용자가 확정하지 않은 위치가 확정 기록이 된다.

### 결정 19 — 점 배치와 트레이 되돌리기를 하나의 `PUT .../placement`로 합친다 (`FN-QUAD-01`)
**문제.** 이전 문서는 `PUT /api/actions/{id}/placement`(놓기·옮기기)와 `PUT /api/actions/{id}/placement/tray`(트레이로 되돌리기)를 따로 두었다. 둘 다 같은 행동의 같은 미확정 위치를 바꾸고 응답도 같다. 경로가 둘이면 클라이언트가 어느 쪽을 부를지 판단해야 하고, 오늘 날짜 검사·소유권 검사·응답 규칙을 두 곳에 같게 구현해야 한다.

**결정.** 행동의 위치(placement)를 하나의 리소스로 보고, `PUT /api/actions/{id}/placement` 하나로 미확정 위치를 지정한다. 트레이는 좌표가 없는 위치이므로 `importance`와 `urgency`를 둘 다 `null`로 보낸다. 서버가 확정 좌표 유무에 따라 `UNCLASSIFIED`·`PENDING`·`RECLASSIFYING`·`PENDING_UNCLASSIFY`를 정하는 규칙은 그대로다. `{ "tray": true }` 같은 별도 플래그 대신 같은 두 필드를 쓰면 요청 모양이 하나이고, "좌표를 지정하거나 좌표 없음을 지정한다"는 한 가지 동작이 된다. 두 필드를 항상 함께 보내게 해 한쪽만 `null`인 요청은 400으로 거른다.

**버린 대안.** (1) 경로 두 개 유지: 위의 중복이 남는다. (2) `DELETE .../placement`로 트레이 되돌리기: 확정 좌표가 있으면 행을 지우지 않고 `PENDING_UNCLASSIFY`로 바꾸는 상태 변경이라 DELETE의 의미와 어긋나고 `DELETE .../placement/pending`(변경 취소)과 헷갈린다. (3) `{ "tray": true }` 플래그: 좌표 필드와 동시에 올 때의 충돌 규칙이 더 필요하다.

### 결정 20 — 연간 리포트 탭은 12월에만, 분기 탭은 3·6·9·12월에만 둔다 (`REVIEW-004`)
**문제.** 진행 중인 연간·분기 기간도 보고 싶을 수 있어, 해당 탭을 항상 보여 줄지 정해야 한다.

**결정.** 연간 리포트는 요구사항(`REVIEW-001`)대로 연말에 보는 리포트라 12월에만, 분기 리포트는 분기 마지막 달에만 탭을 보여 준다. 진행 중인 기간의 탭은 만들지 않는다. 끝나지 않은 기간을 지난 기간과 비교하면 아직 쌓이지 않은 기록 때문에 변화가 작게 보여 성과가 줄어든 것처럼 오해하기 쉽다. 탭 수가 달마다 고정되지 않으면 화면과 서버(`startBase`, 진행 중 표시)가 처리할 경우도 늘어난다. 다만 `GET /api/reports`는 진행 중인 기간도 계산해 주므로(§4.12) 탭이 없을 뿐 API가 막는 것은 아니다.

**버린 대안.** (1) 모든 달에 분기·연간 탭 표시: 위의 오해와 복잡도 문제가 있다. (2) 진행 중 탭에 `진행 중` 배지를 붙여 표시: 배지로도 막대 두 개의 비교 의미가 달라지지 않아 오해를 막지 못한다.
