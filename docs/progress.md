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
| goal-service 테이블 마이그레이션 | 🔶 | ERD. 단위마다 그 단위가 쓰는 테이블만 추가한다(계획 `docs/superpowers/plans/2026-11-16-goal-service.md`). V1 `user_settings` 완료 |
| 공통 도메인 기반 + 설정 `/api/settings` (단위 1) | ✅ | API 명세 §4.1. `ErrorCode` interface + `CommonErrorCode`(도메인 코드는 단위별 enum), `VersionGuard`(사전 비교·커밋 시점 충돌 모두 currentVersion), `CalendarMath`·`UserTime`. Codex 1회차 major 1·minor 1 반영, 2회차 blocker·major 없음. 57 테스트 통과. 서버 실제 MySQL·인스턴스 2대 E2E 21/21(V1 동시 기동 1회 적용, 동시 첫 조회 경합 복구, 동시 PUT 40라운드 200+409·currentVersion) |
| 온보딩 | ⬜ | API 명세 §1.2 |
| 연·월·주 목표, 휴지통·복원 | ⬜ | API 명세 §1.2 |
| 달성 기준, 수치 기록, 진행 기록 | ⬜ | API 명세 §1.2 |
| 행동, 사분면 분류 | ⬜ | API 명세 §1.2 |
| 달력, 대시보드 | ⬜ | API 명세 §1.2 |
| 리포트, 직접 회고 | ⬜ | API 명세 §1.2 |

## 5. 프론트엔드

| 작업 | 상태 | 명세 |
|---|---|---|
| 인증 화면 (가입·로그인·인증·재설정) | ⬜ | 화면 흐름, 시안 |
| 온보딩·대시보드·목표·행동·달력·리포트·설정 | ⬜ | 화면 흐름, 시안 |

## 6. 제출

| 작업 | 상태 | 비고 |
|---|---|---|
| Docker Hub 업로드 (amd64) | ⬜ | |
| 검증 자료 캡처 (`docker ps`, `network inspect`, `exec bash`, 세션 공유, 볼륨 영속성) | ⬜ | 채점 기준 §1.4 |
| 보고서 (구성도, 통신 절차, 설치 가이드, 명령어 전체) | ⬜ | 채점 기준 §1.1·§3 |

## 결정 기록

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
