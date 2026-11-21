# 진행 현황

오케스트레이터(Claude)가 작업 단위를 끝낼 때마다 갱신한다. 새 세션은 이 파일부터 읽고 이어서 작업한다.

상태: ✅ 완료 · 🔶 진행 중·부분 완료 · ⬜ 시작 전 · ⏸ 결정 대기

## 1. 기반

| 작업 | 담당 | 상태 | 비고 |
|---|---|---|---|
| 요구사항·화면·기능·ERD·API 명세 | 오케스트레이터 | ✅ | `docs/aim2day/01`~`05` |
| 서비스 공유 계약 | 오케스트레이터 | ✅ | `docs/aim2day/06_shared-contracts.md` |
| 서비스 뼈대, 서브에이전트, 권한 설정 | 오케스트레이터 | ✅ | |
| Spring Dockerfile·연결 설정 (두 서비스) | user/goal-service-dev | ✅ | 서버(Ubuntu, Docker 29)에서 빌드·기동 확인 |
| 실행 환경 | 사용자 | ✅ | 리눅스 서버 `/opt/aim2day/app`(git pull로 배포), 비밀값은 `/opt/aim2day/env/*.env`(저장소 밖, 600) |
| 컨테이너 8개 기동 | 사용자 + 오케스트레이터 | ✅ | 2026-11-15 전부 Up. 프록시 경유 `/` 200, `/api/**` 401(Spring Security 기본, API 미구현), 별칭 `goal-services` → IP 3개 |
| Nginx 프록시·프론트 이미지 | 사용자(학습) | ✅ | `infra/nginx/`, `frontend/` Dockerfile. 호스트 포트 80 |
| 실행 스크립트 `infra/scripts/` | 사용자 + infra-dev | 🔶 | Docker Compose 금지 |
| 검증 명령 (같은 문서의 확인 절) | 사용자 + 오케스트레이터 | ⬜ | 세션 공유·RR·영속성을 `curl`·`docker` 명령으로 확인 |

## 2. 서비스 공통 기반

| 작업 | 담당 | 상태 | 비고 |
|---|---|---|---|
| 공통 응답·전역 예외 처리 (두 서비스) | user/goal-service-dev | ✅ | 공유 계약 §5. Codex 리뷰 2회차 통과(user 32, goal 34 테스트). 2026-11-16 서버 배포 확인 |
| 보안: 세션 `userId` 인증 필터, CSRF, 401·403 JSON | user/goal-service-dev | ✅ | 공유 계약 §4·§5. 서버에서 401 JSON, CSRF 403, user가 발급한 CSRF 토큰을 goal이 검증하는 것 확인. 실제 Redis 세션 공유는 로그인 구현 후 확인 |
| `X-Instance-Id` 헤더 | goal-service-dev | ✅ | API 명세 §2.6. `InstanceIdFilter`(Security보다 먼저 실행, `HOSTNAME` → `InetAddress`, 실패 시 기동 중단). Codex 리뷰 2회차 통과. 2026-11-16 서버에서 프록시 경유 1→2→3 순환 확인(Nginx 워커별 RR 카운터라 가끔 순서가 어긋남) |
| Swagger(springdoc) | user/goal-service-dev | ✅ | 프록시 경유 공개(공유 계약 §7). 서버에서 `/docs/user/`, `/docs/goal/`의 UI·정적 리소스·api-docs 200 확인 |

## 3. user-service

| 작업 | 상태 | 명세 |
|---|---|---|
| `users` 마이그레이션 | ✅ | ERD(`is_verified` 없음, email `utf8mb4_bin`). Codex 리뷰 완료. 2026-11-16 운영 DB 적용 |
| CSRF 발급, 인증번호 발송·확인, 가입권으로 가입 | ✅ | API 명세 §3.1, 결정 22. 메일은 Gmail SMTP/로그 대체. 80 테스트 통과, 서버 실제 MySQL·Redis E2E 15개 통과. Codex 2회차 후 남은 major 1건(저장 시 제한 소유자 확인)은 사용자 결정으로 3회차 없이 반영·직접 검증. 2026-11-16 운영 배포, 프록시 경유 Gmail 인증번호 수신 확인 |
| 로그인·로그아웃 | ✅ | API 명세 §3.1. Codex 리뷰 minor 3건 반영(재리뷰 생략, major 없음), 99 테스트 통과. 2026-11-16 운영 배포. 서버 E2E: 로그인 SESSION으로 프록시 경유 goal-service 1·2·3 모두 인증 통과, 로그아웃·재로그인 시 옛 쿠키 401, 만료 쿠키 로그아웃 Max-Age=0, createdAt 실제 시각 일치 |
| 비밀번호 재설정 | ✅ | API 명세 §3.1, 결정 14·15. 토큰 발급·소비·정리를 Lua로 원자 처리, 링크는 `#token=`. Codex 1회차(blocker 1·major 4·minor 1) 반영: 로그인 세션 즉시 저장 + 해시 재확인. Codex 2회차 major 2건(검증 로그 원문, 로그인 실패 처리 범위)은 사용자 결정으로 3회차 없이 반영·직접 검증. 138 테스트 통과(실제 Redis 통합 테스트 3개는 IT_REDIS_HOST 있을 때만 실행). 서버 실제 Redis에서 통합 테스트 3/3(즉시 저장, 로그인·재설정 경합 양방향), E2E 재설정 21·로그인 7·세션 공유 15 통과. 2026-11-16 운영 배포 |
| 내 정보 `/api/users/me` | ✅ | API 명세 §3.2. createdAt UTC 값 검증. 2026-11-16 운영 배포 |

## 4. goal-service

| 작업 | 상태 | 명세 |
|---|---|---|
| goal-service 테이블 마이그레이션 | ✅ | ERD. 단위마다 그 단위가 쓰는 테이블만 추가했다(계획 `docs/superpowers/plans/2026-11-16-goal-service.md`). V1~V8 운영 적용 |
| 공통 도메인 기반 + 설정 `/api/settings` (단위 1) | ✅ | API 명세 §4.1. `ErrorCode` interface + `CommonErrorCode`(도메인 코드는 단위별 enum), `VersionGuard`(사전 비교·커밋 시점 충돌 모두 currentVersion), `CalendarMath`·`UserTime`. Codex 1회차 major 1·minor 1 반영, 2회차 blocker·major 없음. 57 테스트 통과. 서버 실제 MySQL·인스턴스 2대 E2E 21/21(V1 동시 기동 1회 적용, 동시 첫 조회 경합 복구, 동시 PUT 40라운드 200+409·currentVersion) |
| 온보딩 (단위 8) | ✅ | API 명세 §4.2. 상태, 임시 저장 GET·PUT·DELETE(JSON 타입, 64KB, version 규칙 4가지), 한 번에 완료(tempId, 전체 롤백). 기존 생성 서비스를 validateNew/insertInTx로 나눠 재사용. 424 테스트·서버 E2E 23/23(`tools/e2e/unit8_onboarding.py`). Codex 2회 반영. V7 배포 |
| 연간 목표 + 달성 기준 + 진행 기록 조회 (단위 2) | ✅ | API 명세 §4.3(삭제 제외)·§4.4·§4.8. 생성·순서 저장·기준 쓰기는 users 행 → 목표 행 순서로 잠금(진행 기록 FK 공유 잠금과의 교착 방지), 순서 저장은 version 미증가, 조회는 REPEATABLE READ 읽기 트랜잭션 하나로 묶음. Codex(gpt-6-astra, Claude 직접 실행) 1회차 major 1·minor 1, 2회차 E2E 도구 major 1·minor 2(시험용 Redis 분리 등)를 3회차 없이 반영·직접 검증. 125 테스트, 서버 E2E 29/29(`tools/e2e/unit2_yearly_criteria.py`) |
| 월간·주간 목표, 진행률 엔진, 수치 기록 (단위 3) | ✅ | API 명세 §4.5·§4.6. 수치 기록은 users → 주간 → 월간 순서로 잠그고 `current_value`를 원자적으로 가산, 월간은 주간 저장 진행률의 가중 평균. 서버 계산 열은 일반 PATCH가 덮어쓰지 않음(`updatable=false`). Codex(사용자 실행) 1회차 major 2(수치 기록 응답을 커밋 뒤 재조회해 생기는 경합, E2E가 두 주간 진행률 동시 변경을 검증 못 함)·minor 3, 2회차 minor 3(E2E 중단 시 정리, 경합 currentVersion 값 검사, 문서) 반영. 195 테스트, 서버 E2E 33/33(`tools/e2e/unit3_monthly_weekly.py`). 단위 4 계획은 계획 문서에 있음 |
| 휴지통·복원 (단위 6) | ✅ | API 명세 §4.7. 삭제·복원은 users 행 잠금으로 같은 사용자의 행동 쓰기와 직렬화, 삭제 시각은 초 단위. 30일 정리는 매시 정각(`AIM2DAY_TRASH_PURGE_CRON`), root마다 users 잠금 → `FOR UPDATE SKIP LOCKED` 재확인 → ERD §4.4 순서(완료 기록 있으면 보존, 멱등). Codex(원격 중 Claude 실행) 1회차 minor 3·사용자 결정 1(remainingDays 날짜 차), 2회차 지적 없음. 334 테스트, 서버 E2E 25/25(`tools/e2e/unit6_trash.py`, 정리 주기 5초) |
| 행동 + 직전 주 요약 (단위 4) | ✅ | API 명세 §4.9·§4.6 previous-summary. 잠금 순서 users → 행동 → 주간(id순) → 월간(id순), 같은 행동 동시 완료는 행동 행 잠금 후 상태 재확인으로 1회 반영, 체크형 분모는 미삭제 또는 완료. 경로가 삭제된 행동은 쓰기 404, MAINTENANCE 색 #9CA3AF(사용자 결정). Codex(원격 중 Claude 실행) 1회차 major 1(같은 월간 내 이동 시 거짓 월간 기록)·minor 3, 2회차 minor 1(문서) 반영. 269 테스트, 서버 E2E 40/40(`tools/e2e/unit4_actions.py`) |
| 사분면 분류 (단위 5) | ✅ | API 명세 §4.10. 좌표 쓰기는 users 행 → 행동 행 잠금으로 직렬화, 좌표 행은 `(행동, 수행일)` 기준, 오늘이 아닌 날짜는 확정 좌표만. 확정 날짜는 `user_settings.last_quadrant_confirmed_date`(V5). `classification` 세 수는 시안처럼 겹치지 않음. Codex(원격 중 Claude 실행) 1회차 minor 2·사용자 결정 3, 2회차 minor 1(회귀 테스트) 반영. 292 테스트, 서버 E2E 30/30(`tools/e2e/unit5_quadrant.py`) |
| 달력 + 대시보드 (단위 7) | ✅ | API 명세 §4.11. 달력 항목 CRUD·완료, 기간 조회(최대 100일), 대시보드. 375 테스트·서버 E2E 28/28(`tools/e2e/unit7_calendar.py`). Codex 1회차 minor 1·계약 확정 반영, 2회차 지적 없음. V6 배포 |
| 리포트, 직접 회고 (단위 9) | ✅ | API 명세 §4.12. 탭(분기·연간은 끝난 뒤에만), 리포트(완료 GOAL 행동·결과물·목표별 달성률 변화 3상태·startBase 2종), 직접 회고 PUT(전체 저장, version 규칙은 온보딩과 같음, V8). 진행 기록은 저장 순서·유효 날짜(log_date 누적 최댓값)로 판정. Codex 1회차 major 2·minor 1·사용자 결정 3 반영, 2회차 지적 없음. 463 테스트·서버 E2E 21/21(`tools/e2e/unit9_reports.py`). V8 배포 |

## 5. 프론트엔드

| 작업 | 상태 | 명세 |
|---|---|---|
| 프론트엔드 계획 | ✅ | `docs/superpowers/plans/2026-11-18-frontend.md`(단위 F1~F6, 화면 E2E는 시험용 프록시 8081 + Playwright) |
| 인증 화면 (가입·로그인·인증·재설정) + 설정 (F1) | ✅ | 화면 E2E 통과, Codex 리뷰 반영 |
| 온보딩(F2)·대시보드(F3)·목표(F4)·달력(F5)·리포트(F6) | ✅ | 화면 E2E 10개 통과(`tools/e2e/web`, 시험용 프록시 8081 + Playwright), Codex 리뷰 5회 반영(사용자 승인으로 2회 한도 초과), 사분면 일괄 취소 API 추가 |

## 6. 제출

| 작업 | 상태 | 비고 |
|---|---|---|
| Docker Hub 업로드 (amd64) | ✅ | 2026-11-21 `jungsooko/aim2day-{user-service,goal-service,frontend,proxy}:1.0`·`latest`(커밋 6937152 이미지). Dockerfile `COPY`로 코드를 넣은 이미지라 바인딩 마운트 없이 실행된다(수업 안내 https://hull.kr/cloud_native/20 과 같은 방식) |
| 검증 자료 캡처 (`docker ps`, `network inspect`, `exec bash`, 세션 공유, 볼륨 영속성) | ⬜ | 채점 기준 §1.4 |
| 보고서 (구성도, 통신 절차, 설치 가이드, 명령어 전체) | ⬜ | 채점 기준 §1.1·§3 |

## 결정 기록

### 응답 지연 (2026-11-19)

- 배포 직후·오래 쉰 뒤 첫 화면이 1~2초 이상 걸리는 원인은 서버 VM 자원(메모리 약 4GB, CPU 2개)이다. JVM 4개와 MySQL이 메모리를 나눠 쓰며 쉬는 JVM이 스왑으로 밀려난다(측정: goal-service RSS 74MB, 스왑 514MB). 메모리가 충분할 때 API는 0.02~0.05초다.
- 사용자 결정: 예열 요청과 JVM 메모리 상한(`-Xmx`)은 넣지 않고 서버 한계로 둔다. 시연 전에는 화면을 한 번씩 열어 둔다.

### 프론트엔드 결정 (2026-11-19, 밤사이 임시 결정 확인)

- 한 페이지 앱 + 해시 라우팅(`#/dashboard`), 파일은 `css/`, `js/api.js`·`router.js`·`ui.js`, `js/views/<화면>.js` ES 모듈(빌드 도구 없음). 월간·주간 설정은 `#/plan`, 온보딩을 마친 사용자의 `#/onboarding`은 대시보드로.
- `X-Instance-Id` 바닥글 표시는 숨긴다(분산 시연은 개발자 도구·로그로).
- 행동 추가에서 고른 월간에 연결할 이번 주 목표가 없으면 화면에서 막고 [주간 목표 만들기]로 안내한다. 서버 422는 그대로 둔다(이중 방어, 화면 흐름 문서와 일치).
- 사분면 [변경 취소]는 새 API `DELETE /api/actions/placements/pending`(API 결정 23)으로 오늘 변경 전체를 한 번에 취소한다.
- "AI 추천 · 준비 중" 문구는 시안·문서대로 둔다.
- 리포트 기준 월은 직접 만든 한국어 선택기("2026년 11월").
- 온보딩 [나중에 하기]: 이번 브라우저 세션 동안 대시보드에 머물고 "이어서 작성하기" 안내를 띄운다. 다시 로그인하면 온보딩부터.
- 온보딩 자동 저장: 마지막 입력 0.8초 뒤, 화면 이동·창 닫기 때도 저장. 저장 충돌(409)이면 화면의 입력을 지키고 [내 입력으로 저장]/[저장된 내용 불러오기] 중 고르게 한다. 저장 전 입력은 브라우저(localStorage)에도 남겨 창을 닫아도 다음 진입 때 고를 수 있다.
- 휴지통 복원 기한 날짜는 계정 시간대로 표시한다.
- 화면 E2E 정리는 서버 정리가 성공한 뒤에만 목록 파일을 지운다.
- Codex 리뷰: 백엔드 정리(c01f0f2) 재리뷰에서 `review_up.sh` 빌드 실패 무시(major)를 고침. 프론트엔드 1차 major 9건·2차 major 6건 반영(2차의 저장 충돌 2건은 위 결정으로 해결).

명세 문서에 반영한 결정은 해당 문서에 있다. 여기에는 문서 밖에서 정한 진행 관련 결정만 적는다.

- 2026-11-14: 이름 규칙은 kebab-case, 접두사 `aim2day-`. 네트워크는 `aim2day-net` 하나.
- 2026-11-14: goal-service 분산은 네트워크 별칭 `goal-services` + Nginx upstream(사용자 선택, 수업 방식). 서버 3대를 직접 나열하는 방식 대신 채택.
- 2026-11-14: 프록시 설정(`default.conf`)과 프론트 정적 파일은 nginx:alpine 기반 Dockerfile로 이미지에 굽는다(Docker Hub 이미지만으로 실행되게). 이 파일들을 호스트 경로 `-v`로 마운트하는 방식은 제출 실행 명령에 쓰지 않는다. MySQL 데이터 볼륨(`-v aim2day-mysql-data:/var/lib/mysql`)은 그대로 쓴다.
- 2026-11-14: MySQL은 `mysql:8.4`(8.0은 2026-04 지원 종료), Redis는 `redis:8-alpine`, Redis 볼륨 없음.
- 2026-11-14: Spring 이미지는 멀티 스테이지(JDK 빌드 → JRE 실행), 실행 단계는 bash가 있는 Ubuntu 기반 `eclipse-temurin:21-jre`. plain jar 생성은 끈다.
- 2026-11-16: Swagger는 프록시 경유로 공개한다(사용자 선택). 서비스별 접두사 `/docs/user/`, `/docs/goal/`(공유 계약 §7).
- 2026-11-16: Nginx 워커가 여러 개라 RR 순번이 워커마다 따로 돈다. 요청 순서가 가끔 1→2→3에서 어긋나는 것은 정상이다.
- 2026-11-16: 서브에이전트를 병렬로 돌릴 때 `./gradlew --stop`을 쓰지 않는다(데몬 공유로 서로의 빌드를 죽임). 빌드·테스트 함정은 `.claude/agents/*-service-dev.md`의 "빌드·테스트 주의"에 모은다.
- 2026-11-16: 실행 스크립트(`infra/scripts/*.sh`)를 만들지 않는다(사용자 결정). 보고서에 직접 실행한 명령으로 적고, 명령어 원본은 `docs/aim2day/07_container-commands.md` 한 곳에 둔다.
- 2026-11-16: DB 비밀번호는 과제 제출물이라 명령어 문서(`07_container-commands.md`)의 `-e`에 그대로 적어 커밋한다(사용자 결정). 저장소는 공개지만 MySQL은 호스트 포트를 열지 않고 VM도 사설망이다. 과제 전용 값으로 쓰고, 출시 전에 바꾼다.
- 2026-11-16: 오류 코드는 도메인을 구현할 때 도메인별 enum으로 나눈다(사용자 결정). 코드 이름 형식은 명세 변경이 필요해 결정 대기.
- 2026-11-16: 오류 코드 이름은 `도메인_뜻`(공통 코드는 접두사 없음), 성공 코드는 `SUCCESS` 하나(사용자 결정, API 명세 결정 21). 명세·두 서비스 ErrorCode·시안 주석의 코드 19개 이름을 바꿨다.
- 2026-11-16: 인증·재설정 메일은 Gmail SMTP로 보낸다(사용자 선택). 자격 증명은 서버의 `/opt/aim2day/env/mail.env`에만 두고, 메일 설정이 없으면 링크를 로그로 출력한다(재현용). Mailpit 컨테이너는 쓰지 않는다. 공유 계약 §2.
- 2026-11-16: 회원가입을 "이메일 인증번호로 먼저 인증 → 가입권으로 가입"으로 바꿨다(사용자 결정, API 명세 결정 22). 미인증 계정·재발송·링크 인증을 없앴다. 비밀번호는 출력 가능한 ASCII만 허용(BCrypt 72바이트). Codex 1회차 지적 중 재발송 응답 시간·가입/재발송 메일 겹침은 새 방식에서 해당 없음, 토큰 교체 원자성은 비밀번호 재설정 단위에 적용.
- 2026-11-16: 비밀번호는 영문 대문자·소문자·숫자·기호를 각각 1자 이상(사용자 결정). 가입·재설정 화면에 규칙을 항상 표시하고 조건별 충족 여부를 보여 주며, 서버 PASSWORD_POLICY 응답 message도 규칙 전체를 알린다.
- 2026-11-16: 재설정 링크는 `#token=`(프래그먼트)로 둬 Nginx 로그·Referer에 토큰이 남지 않게 한다. 로그인은 세션을 즉시 저장한 뒤 비밀번호 해시를 재확인해 재설정과 겹친 로그인 세션이 남지 않게 한다(사용자 결정, Codex 재설정 1회차 blocker·major 4).
- 2026-11-16: goal-service는 단위 9개로 나눠 순서대로 구현한다(계획 `docs/superpowers/plans/2026-11-16-goal-service.md`). 테이블은 단위마다 필요한 것만 마이그레이션으로 추가한다. 상세 작업은 앞 단위 코드가 생긴 뒤 단위별로 덧붙인다.
- 2026-11-16: 검증(빌드·테스트, 리뷰 반영)을 마친 작업 단위는 Claude가 사용자 승인 없이 커밋한다. 푸시는 사용자 요청 시(사용자 결정).
- 2026-11-16: goal-service도 `UserDetailsServiceAutoConfiguration`을 제외한다(기동 시 임시 비밀번호 로그 방지, NFR-010).
- 2026-11-17: 서버 검증(review 컨테이너, 실제 MySQL 통합 테스트)은 같은 MySQL의 `aim2day_review` DB에서 한다(사용자 결정). 리뷰 전 마이그레이션이 운영 DB에 기록된 뒤 파일이 바뀌면 체크섬 불일치로 운영 goal-service가 기동하지 못하기 때문이다. 운영 DB에는 커밋한 최종 마이그레이션만 배포로 적용한다.
- 2026-11-17: 서버 검증은 시험용 Redis(`aim2day-redis-review`)도 따로 쓴다. 운영 Redis를 공유하면 시험용 세션의 userId가 같은 번호의 운영 사용자로 인증될 수 있다(Codex 지적). 실행 도구는 `tools/e2e/`.
- 2026-11-17: 사용자가 자는 동안 Codex 리뷰를 Claude가 직접 실행했다(gpt-6-astra, medium). 사용자가 깬 뒤 승인 규칙을 원래대로 되돌렸고, Codex 리뷰도 다시 사용자가 실행한다.
- 2026-11-17: 사용자가 원격(맥북)으로 작업하는 동안 Codex 리뷰를 Claude가 실행한다. 리뷰 중 사용자가 정할 사안은 반드시 묻는다.
- 2026-11-17: 단위 4 가정 확정(사용자). 연결 경로(주간 또는 조상)가 삭제된 행동은 완료 여부와 관계없이 수정·완료·삭제가 404 `ACTION`이다(숨은 목표의 진행률이 바뀌지 않게). `MAINTENANCE` 행동의 표시 색은 회색 `#9CA3AF` 고정.
- 2026-11-17: 사분면 `confirmedToday`는 `user_settings.last_quadrant_confirmed_date`(새 열, 단위 5 V5)로 판단한다(사용자 결정). 좌표 행에서 유추하면 변경 0건 확정이나 전부 트레이로 되돌린 확정을 놓친다.
- 2026-11-17: 사분면 확정은 상위 목표가 삭제돼도 목록에 남는 완료 행동의 변경을 포함한다(좌표는 진행률과 무관, 사용자 결정). 지난 날짜의 변경 취소는 422 `QUADRANT_DATE_INVALID`, 응답은 PUT과 같은 모양으로 명세 §4.10에 추가했다(사용자 결정). `classification`의 세 수는 시안처럼 겹치지 않게 센다.
- 2026-11-17: goal-service 단위 9까지 끝나면 프런트엔드 전에 백엔드 전체(user-service·goal-service와 두 서비스 사이 공통 부분) 코드 정리(중복 제거 등) 단계를 둔다(사용자 요청).
- 2026-11-17: 온보딩 `draft_data`는 ERD대로 MySQL `JSON` 타입 유지(사용자 결정). H2·MySQL 차이는 서버 E2E에서 `JSON_TYPE`으로 확인한다.
- 2026-11-17: Codex 사용 한도(19:42까지)로 단위 7 2회차 리뷰를 미루고, 사용자 결정으로 한도가 풀릴 때까지 단위 7은 커밋하지 않고 단위 8을 먼저 진행한다.
- 2026-11-17: 달력 계약 확정(사용자): 삭제된 목표의 항목은 필터에서도 미연결, EVENT 종료일 생략 시 유지·`null`이면 하루 일정, 빈 `yearlyGoalIds`는 고른 목표 없음, 보류 목표에도 연결 가능, 설명 2,000자, 지난 일정은 오래된 것부터. 명세 §4.11에 추가.
- 2026-11-17: 휴지통 `remainingDays`는 계정 시간대 날짜 차(0이면 오늘까지, 사용자 결정, 명세 §4.7에 추가).
- 2026-11-17: 휴지통 30일 자동 정리는 매시 정각(사용자 결정). `AIM2DAY_TRASH_PURGE_CRON`으로 바꿀 수 있고 운영은 기본값을 쓴다.
- 2026-11-18: 단위 8 Codex 1회차 major 2건 사용자 결정. ① 임시 저장을 지웠다 다시 만들면 version이 0부터 다시 시작해 예전 탭이 덮어쓸 수 있음 → 처음엔 시작 version을 epoch 밀리초로 했으나 2회차에서 같은 밀리초·시계 역행 시 겹칠 수 있다는 지적에 사용자별 카운터(`user_settings.last_onboarding_draft_version`, V7)로 확정. ② MySQL JSON 숫자 정밀도 → JSON 타입 유지, 보존 보장 범위를 정수 ±2^53·소수 유효숫자 15자리로 명세에 명시. 명세 §4.2에 빈 complete 400, tempId 중복은 같은 단계 안에서만 금지, version 규칙 4가지, 기존 월간 부모 기간은 §4.6(이번 주와 겹침) 추가. 롤백 테스트에 기존 진행률·진행 기록 원복 확인 추가.
- 2026-11-18: 서버 VM이 꺼져 있으면 Claude가 `vmrun -T ws start C:\VMware\master\master.vmx nogui`로 켠다.
- 2026-11-18: 단위 9 사용자 결정. goalPath는 목표의 현재 이름(스냅숏 없음), 회고 PUT은 전체 저장(빠진 필드 null). Codex 1회차 뒤: 진행 기록은 일어난 순서 기준(유효 날짜 = log_date 누적 최댓값, 최초 기준선은 첫 기록), 분기·연간 탭은 그 기간이 끝난 뒤에만(결정 20·REVIEW-004 등 문서 일치), 기간 마지막 날 당일도 진행 중(`오늘 (MM.DD)`). goal-service 9단위 완료, 다음은 백엔드 전체 코드 정리.
- 2026-11-18: 백엔드 코드 정리 범위(사용자 결정). 서비스 사이 중복(공통 응답·예외 처리기·세션·보안 설정, 약 1,100줄 중 실제 공통은 100~200줄)은 공유 모듈 없이 각자 유지하고 계약 문서(06)·테스트로 맞춘다. Dockerfile·제출 빌드 명령을 바꾸지 않기 위해서다. goal-service 안은 동작 불변 리팩터링 4가지(version 판정·재조회 공통화, 목표 수정 필드·문자열 검증 도우미, TransactionTemplate 팩터리, 테스트 기반 클래스·오래된 주석·안 쓰는 import)를 한다. 목표 세 단계 통합·users 잠금 래퍼·FieldErrors DSL은 하지 않는다.
- 2026-11-18: 사용자 요청으로 너무 잘게 나뉜 파일도 정리했다. 요청·응답 클래스(DTO)는 도메인별 한 파일(`XxxDtos`)의 중첩 타입으로 모았다(goal-service main 179→139개, user-service 46→37개). enum·엔티티는 Java 관례대로 따로 둔다. 정리 뒤 테스트 수 동일(goal 463, user 138), 서버 E2E 단위 2~9 229/229(새 user-service 이미지 포함). Codex 리뷰는 사용 한도(11-19 02:12까지)로 커밋 뒤에 돌린다(사용자 동의).
