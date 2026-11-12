# 프로젝트 작업 규칙

작업을 시작할 때 [프로젝트 컨벤션](docs/conventions.md)을 읽는다. 커밋 전에 변경 파일과 메시지가 해당 규칙에 맞는지 확인한다. 커밋과 푸시는 사용자가 요청할 때 수행한다.

## 현재 파일 구조

```text
AGENTS.md                         작업 안내
design/aim2day-prototype.html     화면·상호작용 시안
docs/conventions.md               파일명·커밋 규칙
docs/project-grading-criteria.md  과제 채점·제출 기준
docs/aim2day/01_requirements.md   제품 요구사항과 수용 기준
docs/aim2day/02_user-flow-and-wireframes.md  화면 흐름과 와이어프레임
docs/aim2day/03_functional-specification.md 기능별 입력·처리·검증 규칙
docs/aim2day/04_entity-relationship-diagram.md ERD·테이블 명세와 데이터 결정 사항
```

현재 저장소에는 백엔드·프론트엔드 소스와 API 명세서가 없다. 새 문서를 만들기 전에 기존 파일과 이름을 확인한다.

## 작업별 참고 문서

| 작업 | 먼저 볼 문서 | 함께 확인할 문서 |
|---|---|---|
| 요구사항·기능 변경 | `docs/aim2day/01_requirements.md`, `docs/aim2day/03_functional-specification.md` | 화면에 영향이 있으면 `docs/aim2day/02_user-flow-and-wireframes.md`와 시안 |
| 화면·사용자 흐름 변경 | `docs/aim2day/02_user-flow-and-wireframes.md`, `design/aim2day-prototype.html` | 요구사항과 기능 명세서 |
| API·ERD 설계 | `docs/aim2day/01_requirements.md`, `docs/aim2day/03_functional-specification.md`, `docs/aim2day/04_entity-relationship-diagram.md` | 화면에서 필요한 입력·조회는 화면 흐름 문서, 서비스·DB 제약은 채점 기준 |
| 컨테이너·시연 설계 | `docs/project-grading-criteria.md` | 요구사항의 분산 처리·비기능 요구사항 |

ERD를 설계할 때는 사용자·목표 계층·행동·달력 항목·달성 기준·진행 기록·리포트의 저장 필요성을 요구사항과 기능 명세에서 확인한다. 화면 시안의 예시 데이터와 브라우저 내 임시 상태를 실제 DB 구조로 가정하지 않는다. 문서 간 규칙이 다르면 임의로 합치지 말고 차이를 밝힌 뒤 결정한다.
