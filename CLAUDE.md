# 프로젝트 작업 규칙

이 파일은 Claude(구현·오케스트레이션)용이다. `AGENTS.md`는 Codex(코드 리뷰·평가)용이므로 Claude는 따르지 않는다. "공통 규칙"은 `AGENTS.md`에도 같은 내용이 있으므로, 고칠 때는 두 파일을 함께 고친다.

## 공통 규칙

작업을 시작할 때 [프로젝트 컨벤션](docs/conventions.md)을 읽는다. 커밋 전에 변경 파일과 메시지가 해당 규칙에 맞는지 확인한다. 커밋과 푸시는 사용자가 요청할 때 수행한다.

### 현재 파일 구조

```text
AGENTS.md                         공통 규칙과 Codex(리뷰) 역할
CLAUDE.md                         공통 규칙과 Claude(구현·오케스트레이션) 역할
.claude/agents/                   Claude 서브에이전트 정의(user-service-dev, goal-service-dev, frontend-dev, infra-dev)
.claude/settings.json             Claude 공유 권한 설정
design/aim2day-prototype.html     화면·상호작용 시안
docs/conventions.md               파일명·커밋 규칙
docs/project-grading-criteria.md  과제 채점·제출 기준
docs/aim2day/01_requirements.md   제품 요구사항과 수용 기준
docs/aim2day/02_user-flow-and-wireframes.md  화면 흐름과 와이어프레임
docs/aim2day/03_functional-specification.md 기능별 입력·처리·검증 규칙
docs/aim2day/04_entity-relationship-diagram.md ERD·테이블 명세와 데이터 결정 사항
docs/aim2day/05_api-specification.md API 경로·요청·응답·오류 코드와 화면별 호출 API
docs/aim2day/06_shared-contracts.md   두 서비스·인프라가 함께 지킬 계약(이름, 환경 변수, 세션, Flyway, 프록시)
docs/progress.md                  작업 단위별 진행 현황과 진행 결정 기록
services/user-service/            Spring Boot(Gradle, Java 21) 인증·계정 서비스
services/goal-service/            Spring Boot(Gradle, Java 21) 목표 서비스, 컨테이너 3개로 RR 분산
frontend/                         순수 HTML·CSS·JS, nginx:alpine으로 서빙
infra/nginx/                      역방향 프록시 Nginx 설정
infra/scripts/                    docker run 실행 스크립트(bash, Docker Compose 금지)
```

컨테이너는 reverse-proxy(Nginx), frontend(Nginx), user-service, goal-service-1~3, redis(세션 공유), mysql(영속 볼륨) 8개다. 서비스 코드는 Dockerfile과 연결 설정까지만 있다. 진행 상황은 `docs/progress.md`를 본다. 새 문서를 만들기 전에 기존 파일과 이름을 확인한다.

### 작업별 참고 문서

| 작업 | 먼저 볼 문서 | 함께 확인할 문서 |
|---|---|---|
| 요구사항·기능 변경 | `docs/aim2day/01_requirements.md`, `docs/aim2day/03_functional-specification.md` | 화면에 영향이 있으면 `docs/aim2day/02_user-flow-and-wireframes.md`와 시안 |
| 화면·사용자 흐름 변경 | `docs/aim2day/02_user-flow-and-wireframes.md`, `design/aim2day-prototype.html` | 요구사항과 기능 명세서 |
| API·ERD 설계 | `docs/aim2day/01_requirements.md`, `docs/aim2day/03_functional-specification.md`, `docs/aim2day/04_entity-relationship-diagram.md`, `docs/aim2day/05_api-specification.md`, `docs/aim2day/06_shared-contracts.md` | 화면에서 필요한 입력·조회는 화면 흐름 문서, 서비스·DB 제약은 채점 기준 |
| 컨테이너·시연 설계 | `docs/project-grading-criteria.md`, `docs/aim2day/06_shared-contracts.md` | 요구사항의 분산 처리·비기능 요구사항 |

ERD를 설계할 때는 사용자·목표 계층·행동·달력 항목·달성 기준·진행 기록·리포트의 저장 필요성을 요구사항과 기능 명세에서 확인한다. 화면 시안의 예시 데이터와 브라우저 내 임시 상태를 실제 DB 구조로 가정하지 않는다. 문서 간 규칙이 다르면 임의로 합치지 말고 차이를 밝힌 뒤 결정한다.

## Claude 역할: 구현·오케스트레이션

### 오케스트레이터(메인 세션)

- 사용자 요청을 명세 문서와 대조해 작업 단위로 나눈다. 문서 간 규칙이 다르면 구현 전에 사용자에게 확인한다.
- 작업은 `.claude/agents/`에 정의한 서브에이전트에게 도메인별로 맡긴다. 한 파일을 두 서브에이전트가 동시에 고치지 않게 한다.

| 서브에이전트 | 수정 범위 |
|---|---|
| `user-service-dev` | `services/user-service/` |
| `goal-service-dev` | `services/goal-service/` |
| `frontend-dev` | `frontend/` |
| `infra-dev` | `infra/` |

- 서브에이전트 프롬프트에는 따를 명세 문서·절, 이 대화에서 확정한 결정, 완료 조건(빌드·테스트 통과)을 넣는다.
- 공유 파일은 오케스트레이터가 직접 고친다. `docs/`, `CLAUDE.md`, `AGENTS.md`, `.claude/`, 루트 `.gitignore`, 그리고 두 서비스가 함께 지켜야 하는 계약(Redis 세션 속성 이름·직렬화 방식, 공통 응답 형식, 프록시 경로 접두사)이 해당한다.
- 같은 파일을 여러 작업이 동시에 고쳐야 하는 예외적인 경우에만 Git worktree로 작업 공간을 나눈다.
- 서브에이전트 결과는 diff로 직접 검토하고, 빌드·테스트를 직접 실행해 확인한 뒤 사용자에게 보고한다. 한두 줄짜리 짧은 수정은 직접 해도 된다.
- 작업 단위를 끝내면 `docs/progress.md`의 상태와 결정 기록을 갱신한다. 새 세션은 이 파일부터 읽는다.
- 인프라(Nginx, 실행 스크립트, CI)는 사용자가 배우려는 영역이다. 설정마다 이유를 설명하고, 분산 정책처럼 선택지가 있는 설계는 사용자가 고르게 한다.

### 서브에이전트

- 지정된 디렉터리 밖의 파일은 고치지 않는다. 다른 도메인 변경이 필요하면 고치지 말고 오케스트레이터에게 보고한다.
- 명세에 없는 동작을 임의로 정하지 않는다. 모호하면 가정을 결과에 적는다.
- 커밋하지 않는다. 커밋은 오케스트레이터가 사용자 요청을 받아 한다.

### Codex 리뷰 요청

Codex 리뷰는 사용자가 직접 실행한다. Claude는 Codex를 실행하지 않는다.

1. 리뷰 요청 전에 바뀐 서비스의 `./gradlew build`(테스트 포함)를 통과시킨다. 실패한 상태로 리뷰를 요청하지 않는다.
2. 사용자에게 리뷰 요청문을 준다. 요청문에는 리뷰 범위(브랜치와 기준 커밋, 또는 커밋 전 변경 파일 목록), 관련 명세 문서·절, 실행한 테스트 명령과 결과를 적는다. 구현 설명은 짧게 쓴다. Codex는 설명이 아니라 실제 diff를 기준으로 검토한다.

### Codex 리뷰 반영과 종료 조건

- 사용자가 붙여 넣은 리뷰 결과는 항목마다 코드와 명세로 사실인지 확인한 뒤 반영한다. 틀린 지적은 근거를 들어 반영하지 않는다.
- `blocker`와 `major`는 고친다. `minor`는 사용자에게 목록으로 보고하고, 반영 여부는 사용자가 정한다.
- 반영 작업도 도메인별로 서브에이전트에게 맡기고, 다시 빌드·테스트를 통과시킨 뒤 재리뷰를 요청한다.
- 리뷰와 수정은 한 작업당 최대 2회 반복한다. 2회 뒤에도 `blocker`·`major`가 남거나 Claude와 Codex의 판단이 다르면 멈추고 사용자에게 결정을 받는다.
