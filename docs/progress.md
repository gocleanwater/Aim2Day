# 진행 현황

오케스트레이터(Claude)가 작업 단위를 끝낼 때마다 갱신한다. 새 세션은 이 파일부터 읽고 이어서 작업한다.

상태: ✅ 완료 · 🔶 진행 중·부분 완료 · ⬜ 시작 전 · ⏸ 결정 대기

## 1. 기반

| 작업 | 담당 | 상태 | 비고 |
|---|---|---|---|
| 요구사항·화면·기능·ERD·API 명세 | 오케스트레이터 | ✅ | `docs/aim2day/01`~`05` |
| 서비스 공유 계약 | 오케스트레이터 | ✅ | `docs/aim2day/06_shared-contracts.md` |
| 서비스 뼈대, 서브에이전트, 권한 설정 | 오케스트레이터 | ✅ | |
| Spring Dockerfile·연결 설정 (두 서비스) | user/goal-service-dev | 🔶 | `./gradlew build` 통과. `docker build`는 Docker 미설치로 미검증 |
| Docker Desktop 설치 | 사용자 | ⬜ | 설치 전에는 컨테이너 실행 불가 |
| mysql·redis `docker run` 명령 | 사용자 | 🔶 | 초안 검토 완료, 실행 전 |
| Nginx 프록시 설정 | 사용자(학습) | 🔶 | 뼈대 제공, 사용자 작성 중 |
| 실행 스크립트 `infra/scripts/` | 사용자 + infra-dev | ⬜ | Docker Compose 금지 |
| 스모크 테스트 `infra/scripts/smoke-test.sh` | infra-dev | ⬜ | 세션 공유·RR·영속성 확인 |

## 2. 서비스 공통 기반

| 작업 | 담당 | 상태 | 비고 |
|---|---|---|---|
| 공통 응답·전역 예외 처리 (두 서비스) | user/goal-service-dev | ⬜ | 공유 계약 §5 |
| 보안: 세션 `userId` 인증 필터, CSRF, 401·403 JSON | user/goal-service-dev | ⬜ | 공유 계약 §4·§5 |
| `X-Instance-Id` 헤더 | goal-service-dev | ⬜ | API 명세 §2.6 |
| Swagger(springdoc 3.x) | user/goal-service-dev | ⏸ | 노출 방식 결정 대기: (a) 프록시 경유 / (b) dev 프로필만 |

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
