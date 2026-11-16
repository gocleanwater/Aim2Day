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
| 공통 응답·전역 예외 처리 (두 서비스) | user/goal-service-dev | 🔶 | 공유 계약 §5. 구현·빌드 완료(user 22, goal 32 테스트), Codex 리뷰 대기 |
| 보안: 세션 `userId` 인증 필터, CSRF, 401·403 JSON | user/goal-service-dev | 🔶 | 공유 계약 §4·§5. Codex 리뷰 대기 |
| `X-Instance-Id` 헤더 | goal-service-dev | ✅ | API 명세 §2.6. `InstanceIdFilter`(Security보다 먼저 실행, `HOSTNAME` → `InetAddress`, 실패 시 기동 중단). Codex 리뷰 2회차 통과. 2026-11-16 서버에서 프록시 경유 1→2→3 순환 확인(Nginx 워커별 RR 카운터라 가끔 순서가 어긋남) |
| Swagger(springdoc) | user/goal-service-dev | 🔶 | 프록시 경유 공개, 경로는 공유 계약 §7 |

## 3. user-service

| 작업 | 상태 | 명세 |
|---|---|---|
| `users` 마이그레이션 | ⬜ | ERD |
| CSRF 발급, 가입, 이메일 인증·재발송 | ⬜ | API 명세 §3.1 |
| 로그인·로그아웃 | ⬜ | API 명세 §3.1 |
| 비밀번호 재설정 | ⬜ | API 명세 §3.1 |
| 내 정보 `/api/users/me` | ⬜ | API 명세 §3 |

## 4. goal-service

| 작업 | 상태 | 명세 |
|---|---|---|
| goal-service 테이블 마이그레이션 | ⬜ | ERD |
| 설정 `/api/settings` | ⬜ | API 명세 §1.2 |
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
