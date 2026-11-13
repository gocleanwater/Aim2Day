# 프로젝트 작업 규칙

이 파일은 Claude(구현·오케스트레이션)용이다. `AGENTS.md`는 Codex(코드 리뷰·평가)용이므로 Claude는 따르지 않는다. "공통 규칙"은 `AGENTS.md`에도 같은 내용이 있으므로, 고칠 때는 두 파일을 함께 고친다.

## 공통 규칙

작업을 시작할 때 [프로젝트 컨벤션](docs/conventions.md)을 읽는다. 커밋 전에 변경 파일과 메시지가 해당 규칙에 맞는지 확인한다. 커밋과 푸시는 사용자가 요청할 때 수행한다.

### 현재 파일 구조

```text
AGENTS.md                         공통 규칙과 Codex(리뷰) 역할
CLAUDE.md                         공통 규칙과 Claude(구현·오케스트레이션) 역할
design/aim2day-prototype.html     화면·상호작용 시안
docs/conventions.md               파일명·커밋 규칙
docs/project-grading-criteria.md  과제 채점·제출 기준
docs/aim2day/01_requirements.md   제품 요구사항과 수용 기준
docs/aim2day/02_user-flow-and-wireframes.md  화면 흐름과 와이어프레임
docs/aim2day/03_functional-specification.md 기능별 입력·처리·검증 규칙
docs/aim2day/04_entity-relationship-diagram.md ERD·테이블 명세와 데이터 결정 사항
docs/aim2day/05_api-specification.md API 경로·요청·응답·오류 코드와 화면별 호출 API
```

현재 저장소에는 백엔드·프론트엔드 소스가 없다. 새 문서를 만들기 전에 기존 파일과 이름을 확인한다.

### 작업별 참고 문서

| 작업 | 먼저 볼 문서 | 함께 확인할 문서 |
|---|---|---|
| 요구사항·기능 변경 | `docs/aim2day/01_requirements.md`, `docs/aim2day/03_functional-specification.md` | 화면에 영향이 있으면 `docs/aim2day/02_user-flow-and-wireframes.md`와 시안 |
| 화면·사용자 흐름 변경 | `docs/aim2day/02_user-flow-and-wireframes.md`, `design/aim2day-prototype.html` | 요구사항과 기능 명세서 |
| API·ERD 설계 | `docs/aim2day/01_requirements.md`, `docs/aim2day/03_functional-specification.md`, `docs/aim2day/04_entity-relationship-diagram.md`, `docs/aim2day/05_api-specification.md` | 화면에서 필요한 입력·조회는 화면 흐름 문서, 서비스·DB 제약은 채점 기준 |
| 컨테이너·시연 설계 | `docs/project-grading-criteria.md` | 요구사항의 분산 처리·비기능 요구사항 |

ERD를 설계할 때는 사용자·목표 계층·행동·달력 항목·달성 기준·진행 기록·리포트의 저장 필요성을 요구사항과 기능 명세에서 확인한다. 화면 시안의 예시 데이터와 브라우저 내 임시 상태를 실제 DB 구조로 가정하지 않는다. 문서 간 규칙이 다르면 임의로 합치지 말고 차이를 밝힌 뒤 결정한다.

## Claude 역할: 구현·오케스트레이션

### 오케스트레이터(메인 세션)

- 사용자 요청을 명세 문서와 대조해 작업 단위로 나눈다. 문서 간 규칙이 다르면 구현 전에 사용자에게 확인한다.
- 작업은 겹치지 않는 도메인으로 나눠 경량 모델(sonnet, haiku) 서브에이전트에게 맡긴다. 기본 단위는 서비스(`user-service`, `goal-service` 등)와 프론트엔드다. 한 파일을 두 서브에이전트가 동시에 고치지 않게 한다.
- 서브에이전트 프롬프트에는 담당 디렉터리, 따를 명세 문서·절, 이 대화에서 확정한 결정, 완료 조건(빌드·테스트 통과)을 넣는다.
- 공유 계약(API 명세, ERD, 공통 설정, `docker-compose` 등 여러 도메인이 함께 쓰는 파일)은 오케스트레이터가 직접 고치거나 한 서브에이전트에게만 맡긴다.
- 서브에이전트 결과는 diff로 직접 검토하고, 빌드·테스트를 실행해 확인한 뒤 사용자에게 보고한다. 한두 줄짜리 짧은 수정은 직접 해도 된다.

### 서브에이전트

- 지정된 디렉터리 밖의 파일은 고치지 않는다. 다른 도메인 변경이 필요하면 고치지 말고 오케스트레이터에게 보고한다.
- 명세에 없는 동작을 임의로 정하지 않는다. 모호하면 가정을 결과에 적는다.
- 커밋하지 않는다. 커밋은 오케스트레이터가 사용자 요청을 받아 한다.

### Codex 리뷰 반영

- Codex 리뷰 결과는 항목마다 코드와 명세로 사실인지 확인한 뒤 반영한다. 틀린 지적은 근거를 들어 반영하지 않는다.
- 반영 작업도 도메인별로 서브에이전트에게 나눠 맡긴다.
