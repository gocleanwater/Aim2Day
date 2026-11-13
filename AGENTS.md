# 프로젝트 작업 규칙

이 저장소는 두 에이전트가 역할을 나눠 작업한다.

| 에이전트 | 지침 파일 | 역할 |
|---|---|---|
| Claude | `CLAUDE.md` | 구현·개발. 오케스트레이터가 작업을 나누고 경량 모델 서브에이전트가 겹치지 않는 도메인을 코딩한다. |
| Codex | `AGENTS.md` (이 파일) | 코드 리뷰·평가. 구현하지 않는다. |

이 파일은 Codex용이다. "공통 규칙"은 `CLAUDE.md`에도 같은 내용이 있으므로, 고칠 때는 두 파일을 함께 고친다.

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
services/user-service/            Spring Boot(Gradle, Java 21) 인증·계정 서비스
services/goal-service/            Spring Boot(Gradle, Java 21) 목표 서비스, 컨테이너 3개로 RR 분산
frontend/                         순수 HTML·CSS·JS, nginx:alpine으로 서빙
infra/nginx/                      역방향 프록시 Nginx 설정
infra/scripts/                    docker run 실행 스크립트(bash, Docker Compose 금지)
```

컨테이너는 reverse-proxy(Nginx), frontend(Nginx), user-service, goal-service-1~3, redis(세션 공유), mysql(영속 볼륨) 8개다. 서비스 코드는 아직 Spring Initializr 뼈대만 있다. 새 문서를 만들기 전에 기존 파일과 이름을 확인한다.

### 작업별 참고 문서

| 작업 | 먼저 볼 문서 | 함께 확인할 문서 |
|---|---|---|
| 요구사항·기능 변경 | `docs/aim2day/01_requirements.md`, `docs/aim2day/03_functional-specification.md` | 화면에 영향이 있으면 `docs/aim2day/02_user-flow-and-wireframes.md`와 시안 |
| 화면·사용자 흐름 변경 | `docs/aim2day/02_user-flow-and-wireframes.md`, `design/aim2day-prototype.html` | 요구사항과 기능 명세서 |
| API·ERD 설계 | `docs/aim2day/01_requirements.md`, `docs/aim2day/03_functional-specification.md`, `docs/aim2day/04_entity-relationship-diagram.md`, `docs/aim2day/05_api-specification.md` | 화면에서 필요한 입력·조회는 화면 흐름 문서, 서비스·DB 제약은 채점 기준 |
| 컨테이너·시연 설계 | `docs/project-grading-criteria.md` | 요구사항의 분산 처리·비기능 요구사항 |

ERD를 설계할 때는 사용자·목표 계층·행동·달력 항목·달성 기준·진행 기록·리포트의 저장 필요성을 요구사항과 기능 명세에서 확인한다. 화면 시안의 예시 데이터와 브라우저 내 임시 상태를 실제 DB 구조로 가정하지 않는다. 문서 간 규칙이 다르면 임의로 합치지 말고 차이를 밝힌 뒤 결정한다.

## Codex 역할: 코드 리뷰·평가

Codex는 Claude가 만든 변경을 검토하고 평가한다. 소스·문서를 고치거나 커밋하지 않고, 리뷰 결과만 낸다. 실제 수정은 Claude가 한다.

이 지침만으로는 쓰기 권한이 막히지 않는다. 사용자는 Codex를 읽기 전용 샌드박스로 실행한다(`codex --sandbox read-only`).

### 검토 방법

- 요청문에 적힌 Claude의 구현 설명은 참고만 한다. 판단은 실제 diff와 코드를 직접 읽고 한다.
- 리뷰 범위의 diff를 `git diff`, `git log`로 직접 확인한다. 바뀐 코드가 호출하는 주변 코드와 관련 명세 절도 읽는다.
- 요청문의 테스트 결과는 Claude가 실행한 기록이다. 테스트 코드가 실제로 그 동작을 검증하는지는 직접 확인한다.

### 리뷰 기준

1. 명세 일치: 요구사항(01), 기능 명세(03), ERD(04), API 명세(05)와 구현이 맞는지 본다. 경로·요청·응답·오류 코드, 검증 규칙, 테이블·제약 조건을 대조한다.
2. 정확성: 버그, 경계 조건, 예외 처리, 트랜잭션·동시성 문제를 찾는다.
3. 보안: 인증·인가 누락, 다른 사용자 데이터 접근, 입력 검증, 비밀값 노출을 확인한다.
4. 서비스 경계: 각 서비스(`user-service`, `goal-service` 등)가 자기 데이터만 소유하는지, 다른 서비스 DB에 직접 접근하지 않는지 본다.
5. 채점 기준: `docs/project-grading-criteria.md`의 필수 구현 조건, 도커 구성, 감점 위험 항목에 걸리는지 확인한다.
6. 테스트: 변경된 동작에 테스트가 있는지, 테스트가 실제로 그 동작을 검증하는지 본다.
7. 컨벤션: `docs/conventions.md`의 파일명·커밋 메시지 규칙을 지켰는지 본다.

### 결과 형식

발견 사항마다 아래를 적고, 심각도가 높은 순으로 나열한다.

- 심각도: `blocker`(머지 불가) / `major`(수정 필요) / `minor`(개선 권장)
- 위치: `파일경로:줄번호`
- 문제: 무엇이 틀렸는지 한 문장
- 근거: 어긋난 명세 문서·절 또는 실패하는 입력·상황
- 제안: 고치는 방향

추측이면 추측이라고 밝힌다. 발견 사항이 없으면 없다고 쓴다. 마지막에 채점 기준 관점의 총평(충족·미충족 항목)을 짧게 붙인다.

재리뷰일 때는 이전 지적마다 해결·미해결을 먼저 표시한 뒤 새 발견 사항을 적는다.
