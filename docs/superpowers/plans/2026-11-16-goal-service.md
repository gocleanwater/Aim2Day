# goal-service 도메인 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** API 명세 §4의 goal-service 엔드포인트(설정·온보딩·목표·기준·행동·사분면·달력·리포트)를 구현해 3개 인스턴스 RR 환경에서 동작시킨다.

**Architecture:** 범위가 커서 **단위 9개**로 나누고 의존 순서대로 진행한다. 각 단위는 "마이그레이션 + 엔티티 + 서비스 + 컨트롤러 + 테스트"를 함께 끝내고, user-service 때와 같은 흐름(서브에이전트 구현 → 오케스트레이터 검토·빌드 → 서버 E2E → Codex 리뷰 → 커밋·배포)을 따른다. 이 문서는 전체 로드맵과 **단위 1의 상세 작업**을 담는다. 단위 2부터는 앞 단위의 실제 코드가 생긴 뒤 같은 형식으로 상세 작업을 덧붙인다(미리 쓰면 앞 단위에서 바뀐 이름·시그니처와 어긋난다).

**Tech Stack:** Java 21, Spring Boot 4.1.2, Spring Data JPA(Hibernate), Flyway(MySQL 8.4), Spring Session Redis, JUnit 5 + MockMvc, 테스트 DB H2(MODE=MySQL)

**Spec:** `docs/aim2day/05_api-specification.md` §2·§4, `docs/aim2day/04_entity-relationship-diagram.md` §3·§4·§6, `docs/aim2day/06_shared-contracts.md`

## Global Constraints

- 코드 위치는 `services/goal-service/`만. 문서·공유 계약은 오케스트레이터가 고친다.
- 스키마는 Flyway만 만든다. `spring.jpa.hibernate.ddl-auto=validate`, 이력 테이블 `flyway_goal_service_history`(공유 계약).
- 일시는 UTC(`Instant`), 날짜는 `LocalDate`(`DATE`). "오늘·이번 주·이번 달"은 `user_settings.timezone`·`week_start_day`로 서버가 계산한다(§2.3). 시각은 주입한 `Clock`에서만 얻는다.
- 진행률은 `BigDecimal.setScale(2, RoundingMode.HALF_UP)`. `current_value`·`progress_rate`는 직접 UPDATE로만 바꾸고 엔티티에서는 `@Column(updatable = false)`(ERD 결정 16·17).
- 잠금 순서: `users` 행 → 주간 목표 → 월간 목표. 바꾸지 않는다.
- 다른 사용자 리소스, 삭제·숨김 리소스는 404 `RESOURCE_NOT_FOUND`(§2.5).
- `VERSION_CONFLICT`의 `details.currentVersion`은 도메인 서비스가 채운다(공유 계약 §5). 사전 비교는 `VersionGuard.check`, 커밋 시점 충돌은 `VersionGuard.translateConflict`로 **가장 바깥 트랜잭션을 감싸** 처리하고, 현재 version은 새 트랜잭션에서 읽는다(`SettingsService.update`가 기준 구현).
- `SettingsService.userTime()`/`getOrCreate()`는 호출자 트랜잭션과 분리된 새 트랜잭션에서 읽으므로 반환 엔티티는 읽기 전용으로만 쓴다.
- 여러 테이블을 읽어 한 응답을 만드는 조회는 읽기 전용 트랜잭션 하나(`ISOLATION_REPEATABLE_READ`)로 묶어 한 시점의 스냅샷으로 읽는다(단위 2 Codex 지적).
- 동시 수정 E2E는 경쟁하는 요청들이 현재 값과 다른 값을 보내게 만든다. 같은 값이면 Hibernate가 UPDATE를 생략해 version 검사 없이 200이 된다(정상 동작이지만 경합 검증이 안 된다).
- 오류 코드는 도메인별 enum(결정 21: `도메인_뜻`, 공통 코드는 접두사 없음).
- 테스트 설정은 `src/test/resources/config/`에 둔다. 세션은 `support/TestSessionConfig`를 재사용한다. `./gradlew --stop`을 쓰지 않는다(`.claude/agents/goal-service-dev.md` "빌드·테스트 주의").
- 서브에이전트는 커밋하지 않는다.

## 전체 로드맵

| 단위 | 범위 | API 명세 | 새 테이블 | 의존 |
|---|---|---|---|---|
| 1 | 공통 도메인 기반 + 설정 | §4.1 | `user_settings` | 없음 |
| 2 | 연간 목표 + 달성 기준 + 진행 기록 조회 | §4.3(상세 트리 제외)·§4.4·§4.8 | `yearly_goals`, `yearly_goal_criteria`, `goal_progress_logs` | 1 |
| 3 | 월간·주간 목표 + 진행률 엔진 + 수치 기록 | §4.5·§4.6(직전 주 요약 제외), §4.3 상세 트리 | `monthly_goals`, `weekly_goals`, `weekly_goal_numeric_records` | 2 |
| 4 | 행동 + 직전 주 요약 | §4.9, §4.6 `previous-summary` | `actions` | 3 |
| 5 | 사분면 분류 | §4.10 (+ §4.9 `placement`·정렬) | `action_quadrant_placements` | 4 |
| 6 | 삭제·휴지통·복원 + 30일 정리 작업 | §4.7 | 없음 | 4 |
| 7 | 달력 + 대시보드 | §4.11 | `calendar_items` | 2 |
| 8 | 온보딩 | §4.2 | `onboarding_drafts` | 2·3·4 (생성 서비스 재사용) |
| 9 | 리포트 + 직접 회고 | §4.12 | `period_reviews` | 2·4 |

순서 근거:
- **진행률 엔진(단위 3)이 핵심 위험이다.** 행동 완료(4)·삭제(6)·온보딩(8)이 모두 이를 호출한다. 그래서 먼저 만들고, 실제 MySQL 동시성 통합 테스트로 굳힌다.
- 삭제(6)는 "숨겨질 미완료 행동 수"를 세야 하므로 행동(4) 뒤에 둔다.
- 달력(7)은 연간 목표만 참조해 2 이후 아무 때나 가능하다. 다만 같은 서비스·같은 Gradle 빌드라 병렬로 돌리지 않는다.

### 단위마다 공통 완료 조건

1. `./gradlew build` 통과(H2 단위·슬라이스 테스트).
2. 동시성·잠금이 걸린 단위(2·3·4·6)는 **시험용 DB(`aim2day_review`) 대상 서버 E2E 스크립트**를 `tools/e2e/`에 남겨 다시 돌릴 수 있게 한다(2026-11-17 변경, 처음 계획의 `IT_MYSQL_URL` 통합 테스트 대신). H2는 `FOR UPDATE SKIP LOCKED`·행 잠금·격리 수준을 MySQL과 같게 재현하지 못한다. H2 테스트는 규칙과 경로를, E2E는 실제 MySQL 동작을 확인한다.
3. 서버 review 컨테이너로 프록시 경유 E2E(3개 인스턴스에 나뉘어 처리되는지 `X-Instance-Id`로 확인).
4. Codex 리뷰 최대 2회 → 커밋·푸시 → 운영 배포 → `progress.md` 갱신.

### 단위별 결정 대기 사항 (그 단위 시작 전에 묻는다)

- 단위 2: `GET /api/goals/yearly/{id}`의 하위 트리는 단위 3에서 채운다(단위 2에서는 빈 배열). 명세 변경은 없다.
- 단위 6: 30일 정리 예약 작업의 실행 주기(예: 매시 정각). 명세에 없다.
- 단위 8: `draft_data` JSON 열을 H2 테스트에서 어떻게 다룰지(H2 `JSON` 타입과 MySQL `JSON`의 매핑 차이).

## Review Focus (단위 1)

1. **두 인스턴스가 같은 사용자의 첫 `GET /api/settings`를 동시에 받음** → 한쪽이 UNIQUE 위반을 맞는다. 두 요청 모두 200이고 행은 1개여야 한다. → Task 3의 `concurrentFirstReadCreatesOneRow`.
2. **IANA 이름이 아닌 시간대**(`+09:00`, `KST`, `asia/seoul`) → `ZoneId.of`는 오프셋을 받아들이므로 그대로 쓰면 통과한다. 400 `VALIDATION_FAILED`(`INVALID_FORMAT`)여야 한다. → Task 3의 `rejectsNonIanaTimezone`.
3. **오래된 `version`으로 PUT** → 409 `VERSION_CONFLICT`, `details.currentVersion`이 DB 값. → Task 3.
4. **주 경계 계산**: 오늘이 주 시작 요일 당일, 연말을 넘는 주(2026-12-31). → Task 2.
5. **시간대 날짜 경계**: UTC 2026-11-12T15:30Z는 서울 기준 11-13. → Task 2.

---

## 단위 1: 공통 도메인 기반 + 설정

### Task 1: 오류 코드 도메인 분리와 version 검사 도우미

**Files:**
- Modify: `src/main/java/com/aim2day/goal/common/exception/ErrorCode.java` (enum → interface)
- Create: `src/main/java/com/aim2day/goal/common/exception/CommonErrorCode.java`
- Create: `src/main/java/com/aim2day/goal/common/exception/VersionGuard.java`
- Modify: `BusinessException.java`, `GlobalExceptionHandler.java`, `SecurityErrorHandlers.java`, `ApiErrorController.java`, `ErrorResponse.java` (enum 상수 참조를 `CommonErrorCode`로)
- Test: `src/test/java/com/aim2day/goal/common/exception/VersionGuardTests.java`, 기존 `ErrorHandlingTests` 유지

**Interfaces:**
- Produces:
  - `interface ErrorCode { String name(); HttpStatus status(); String defaultMessage(); }`. enum이 구현하면 `name()`은 자동이다. 응답 `code`는 `name()`이다.
  - `enum CommonErrorCode implements ErrorCode`: `MALFORMED_REQUEST, VALIDATION_FAILED, UNAUTHENTICATED, CSRF_TOKEN_INVALID, RESOURCE_NOT_FOUND, VERSION_CONFLICT, INTERNAL_ERROR`(기존 메시지 그대로)
  - `final class VersionGuard { static void check(Long requested, long current) }`: `requested`가 `null`이면 `BusinessException(VALIDATION_FAILED)`(fieldErrors `version`/`REQUIRED`), 다르면 `BusinessException(VERSION_CONFLICT, Map.of("currentVersion", current))`.
  - 도메인 코드(`GOAL_*`, `CRITERIA_*`, `ACTION_*` 등)는 이 Task에서 **지운다**. 각 단위가 자기 도메인 패키지에 enum을 만든다(예: 단위 2의 `com.aim2day.goal.yearly.GoalErrorCode`).

- [ ] **Step 1: 실패하는 테스트 작성**: `VersionGuardTests`
  - `sameVersionPasses`: `check(3L, 3)`이 예외 없음
  - `staleVersionThrowsConflictWithCurrentVersion`: `check(2L, 3)` → `getErrorCode() == CommonErrorCode.VERSION_CONFLICT`, `getDetails()`가 `{currentVersion=3}`
  - `missingVersionIsValidationFailed`: `check(null, 3)` → `VALIDATION_FAILED`, details의 fieldErrors[0]이 `version`/`REQUIRED`
  - `ErrorHandlingTests`에 `domainCodeImplementingInterfaceIsRendered`를 추가한다. 테스트 전용 enum `TestErrorCode.SAMPLE_RULE(UNPROCESSABLE_CONTENT, "...")`을 `TestApiController`에서 던지면 422, `$.code == "SAMPLE_RULE"`.
- [ ] **Step 2: 실행해 실패 확인**: `./gradlew test --tests '*VersionGuardTests' --tests '*ErrorHandlingTests'` → 컴파일 실패
- [ ] **Step 3: 구현**: interface 추출, 기존 참조 교체, `VersionGuard` 작성. `GlobalExceptionHandler`의 `OptimisticLockingFailureException` 처리(details null)는 마지막 안전망으로만 둔다. 공유 계약 §5대로 커밋 시점 충돌도 서비스가 잡아 `currentVersion`을 채운다. 사전 비교를 통과한 두 요청이 같은 version으로 저장하는 경우다. 이를 위해 `VersionGuard`에 트랜잭션 바깥에서 쓰는 변환 도우미를 둔다(Task 3에서 사용).
- [ ] **Step 4: 전체 테스트 통과 확인**: `./gradlew test` → 기존 34개 + 신규 모두 PASS

### Task 2: 시각·기간 계산

**Files:**
- Create: `src/main/java/com/aim2day/goal/common/time/ClockConfig.java`: `@Bean Clock clock()`가 `Clock.systemUTC()`
- Create: `src/main/java/com/aim2day/goal/common/time/DateRange.java`: `record DateRange(LocalDate startDate, LocalDate endDate)`
- Create: `src/main/java/com/aim2day/goal/common/time/CalendarMath.java`
- Test: `src/test/java/com/aim2day/goal/common/time/CalendarMathTests.java`

**Interfaces:**
- Produces (`CalendarMath`, 상태 없는 static):
  - `static LocalDate today(Clock clock, ZoneId zone)`
  - `static DateRange weekContaining(LocalDate date, DayOfWeek weekStart)`: 시작일은 `date` 이전이거나 같은 가장 가까운 `weekStart`, 끝은 +6일
  - `static DateRange monthOf(YearMonth month)`
- 단위 2 이후 모든 "오늘·이번 주·이번 달" 판단은 이 메서드와 Task 3의 `UserTime`을 거친다.

- [ ] **Step 1: 실패하는 테스트 작성**: `CalendarMathTests`
  - `sundayStartWeek`: `weekContaining(2026-11-12, SUNDAY)` == `2026-11-08..2026-11-14`
  - `mondayStartWeek`: `(2026-11-12, MONDAY)` == `2026-11-09..2026-11-15`
  - `dateOnWeekStartDayStartsThatDay`: `(2026-11-08, SUNDAY)` == `2026-11-08..2026-11-14`
  - `weekCrossesYearEnd`: `(2026-12-31, SUNDAY)` == `2026-12-27..2027-01-02`
  - `todayUsesAccountTimezone`: `Clock.fixed(2026-11-12T15:30:00Z)` → Seoul은 `2026-11-13`, UTC는 `2026-11-12`
  - `februaryLeapYear`: `monthOf(2028-02)` == `2028-02-01..2028-02-29`
- [ ] **Step 2: 실행해 실패 확인**: `./gradlew test --tests '*CalendarMathTests'` → 컴파일 실패
- [ ] **Step 3: 구현**: `weekContaining`은 `date.with(TemporalAdjusters.previousOrSame(weekStart))`
- [ ] **Step 4: 통과 확인**: 같은 명령 → PASS

### Task 3: `user_settings` 마이그레이션과 설정 API

**Files:**
- Create: `src/main/resources/db/migration/V1__create_user_settings.sql`: ERD 3.1-2 그대로. `uk_us_user_id` UNIQUE(`user_id`), FK `users(id)` ON DELETE CASCADE, InnoDB utf8mb4
- Create: `src/test/resources/db/test-migration/V0_1__users_stub.sql`: H2용 최소 `users(id BIGINT PK)`. 운영에서는 user-service가 만든다.
- Modify: `src/test/resources/config/application.properties`: `spring.flyway.locations=classpath:db/migration,classpath:db/test-migration`, `fail-on-missing-locations` 줄 삭제
- Modify: `src/main/resources/application.properties`: `spring.jpa.hibernate.ddl-auto=validate`, `spring.jpa.open-in-view=false`
- Create: `src/main/java/com/aim2day/goal/settings/UserSetting.java` (엔티티, `@Version Long version`)
- Create: `src/main/java/com/aim2day/goal/settings/UserSettingRepository.java`
- Create: `src/main/java/com/aim2day/goal/settings/UserTime.java`
- Create: `src/main/java/com/aim2day/goal/settings/SettingsService.java`
- Create: `src/main/java/com/aim2day/goal/settings/SettingsController.java`
- Create: `src/main/java/com/aim2day/goal/settings/dto/SettingsResponse.java`, `UpdateSettingsRequest.java`
- Test: `src/test/java/com/aim2day/goal/settings/SettingsApiTests.java`, `SettingsServiceConcurrencyTests.java`

**Interfaces:**
- Consumes: `CalendarMath`, `DateRange`, `VersionGuard`, `Clock` 빈
- Produces:
  - `record UserTime(ZoneId zone, DayOfWeek weekStart, LocalDate today, DateRange currentWeek, YearMonth currentMonth)`: `DateRange currentMonthRange()`도 둔다
  - `SettingsService.getOrCreate(long userId) -> UserSetting`(없으면 SUNDAY·Asia/Seoul로 만든다)
  - `SettingsService.userTime(long userId) -> UserTime`: **단위 2 이후 모든 서비스가 "오늘"을 이걸로 얻는다**
  - `SettingsService.update(long userId, UpdateSettingsRequest req) -> UserSetting`
- 응답 형식은 §4.1 그대로: `weekStartDay, timezone, version, today, currentWeek{startDate,endDate}, currentMonth{month,startDate,endDate}`

- [ ] **Step 1: 실패하는 테스트 작성**: `SettingsApiTests`(MockMvc, `SecurityTests.loginCookie` 방식으로 세션 생성, `Clock`은 `@TestConfiguration`으로 `2026-11-12T03:00:00Z` 고정)
  - `firstGetCreatesDefaults`: `$.data.weekStartDay=="SUNDAY"`, `timezone=="Asia/Seoul"`, `version==0`, `today=="2026-11-12"`, `currentWeek.startDate=="2026-11-08"`, `currentMonth.month=="2026-11"`, `currentMonth.endDate=="2026-11-30"`
  - `putChangesWeekStartAndBumpsVersion`: PUT `{MONDAY, Asia/Seoul, version 0}` → 200, `version==1`, `currentWeek.startDate=="2026-11-09"`
  - `staleVersionIs409WithCurrentVersion`: 위 뒤에 다시 `version 0` → 409, `$.code=="VERSION_CONFLICT"`, `$.details.currentVersion==1`
  - `rejectsNonIanaTimezone`: `"+09:00"`, `"KST"`, `"asia/seoul"` 각각 → 400 `VALIDATION_FAILED`, `fieldErrors[0].field=="timezone"`, `reason=="INVALID_FORMAT"`
  - `unknownWeekStartDayIsMalformed`: `"FUNDAY"` → 400 `MALFORMED_REQUEST`
  - `missingVersionIs400`: version 없음 → 400 `VALIDATION_FAILED`, field `version`
  - `putWithoutCsrfIs403`: CSRF 헤더 없음 → 403 `CSRF_TOKEN_INVALID`
  - `usersAreIsolated`: 사용자 1이 MONDAY로 바꿔도 사용자 2의 GET은 SUNDAY
- [ ] **Step 2: `SettingsServiceConcurrencyTests` 작성**: `concurrentFirstReadCreatesOneRow`. 스레드 2개가 `CountDownLatch`로 동시에 `getOrCreate(7)` → 둘 다 예외 없음, `repository.count()==1`
- [ ] **Step 3: 실행해 실패 확인**: `./gradlew test --tests '*Settings*'` → 실패
- [ ] **Step 4: 구현**
  - 시간대 검증은 `ZoneId.getAvailableZoneIds().contains(value)`로 한다(대소문자 정확히, 오프셋·약어 거부).
  - `getOrCreate`는 조회 → 없으면 `saveAndFlush`를 **별도 트랜잭션**(`REQUIRES_NEW` 또는 `TransactionTemplate`)에서 시도하고, `DataIntegrityViolationException`이면 다시 조회한다. 같은 트랜잭션에서 재조회하면 실패한 flush로 세션이 오염된다.
  - `update`는 `getOrCreate` → `VersionGuard.check(req.version(), entity.getVersion())` → 필드 변경 → `saveAndFlush`. 응답 `version`은 flush 뒤의 값이어야 한다.
  - `created_at`·`updated_at`은 DB 기본값에 맡긴다(`insertable=false, updatable=false`). 응답에 쓰지 않는다.
- [ ] **Step 5: 전체 통과 확인**: `./gradlew build` → BUILD SUCCESSFUL

### Task 4: 서버 검증 (오케스트레이터)

- [ ] **Step 1: 마이그레이션 순서 확인**: 서버 review 이미지로 goal-service 1개 기동 → `flyway_goal_service_history`에 V1 성공, `SHOW CREATE TABLE user_settings`에 FK·UNIQUE 확인. users가 이미 있으므로 FK가 생성된다. 3개 인스턴스가 동시에 기동해도 Flyway가 DB 잠금으로 한 번만 적용하는 것을 로그로 확인한다.
- [ ] **Step 2: 프록시 경유 E2E**: 로그인 세션으로 `GET /api/settings`를 3번 → `X-Instance-Id` 3종이 같은 값을 반환. PUT은 `version` 0 → 200, 다른 인스턴스에서 `version` 0 → 409 `currentVersion` 1.
- [ ] **Step 3: 정리**: 테스트 사용자·설정 행 삭제(테이블 DROP 금지), review 컨테이너·이미지 제거.
- [ ] **Step 4: Codex 리뷰 요청문 제공 → 반영 → 커밋·푸시·운영 배포 → `progress.md` 갱신**

> 운영 배포 순서 주의: goal-service V1이 `users(id)`를 FK로 참조하므로 user-service가 먼저 마이그레이션되어 있어야 한다. 운영 DB에는 이미 있으므로 문제없다. 빈 DB로 처음부터 띄울 때는 07 문서 순서(user-service → goal-service)를 따른다.

---

## 단위 2: 연간 목표 + 달성 기준 + 진행 기록 조회

**범위:** API 명세 §4.3(삭제 제외, 상세의 `monthlyGoals`는 빈 배열), §4.4, §4.8. 삭제·복원은 단위 6에서 한다.
**새 테이블:** `V2__create_yearly_goals.sql`에 `yearly_goals`, `yearly_goal_criteria`, `goal_progress_logs`를 만든다(ERD 3.2-4·5, 3.5-11 그대로, 인덱스 이름 ERD대로).

### 이 단위에서 정한 것 (명세에 없던 세부)

- **패키지:** `com.aim2day.goal.goals`(목표 단계 공통: `GoalStatus`, `GoalErrorCode`), `goals.yearly`, `goals.criteria`(`CriteriaErrorCode`), `progress`(진행 기록). 단위 3은 `goals.monthly`, `goals.weekly`를 더한다.
- **오류 코드 enum:** `GoalErrorCode`(이번 단위에서 쓰는 `GOAL_LIMIT_EXCEEDED`, `GOAL_ORDER_MISMATCH`), `CriteriaErrorCode`(`CRITERIA_ALREADY_CONFIRMED`, `CRITERIA_WEIGHT_SUM_INVALID`, `CRITERIA_NOT_CONFIRMED`). 메시지는 단위 1 이전 `ErrorCode`의 문장을 그대로 쓴다(`git show 32d78ba:services/goal-service/src/main/java/com/aim2day/goal/common/exception/ErrorCode.java`).
- **사용자 행 잠금:** `common/lock/UserRowLock.lock(long userId)`가 `SELECT id FROM users WHERE id = ? FOR UPDATE`를 실행한다. 연간 목표 생성·순서 저장에서 첫 쿼리로 부른다(ERD §4.1).
- **잠금 순서 추가:** 기준 저장·확정·달성은 연간 목표 행을 `PESSIMISTIC_WRITE`로 먼저 잠근다. 이 경로는 주간·월간 행을 잠그지 않으므로 기존 순서(users → 주간 → 월간)와 교착하지 않는다.
- **순서 저장은 version을 올리지 않는다:** `display_order`는 직접 UPDATE로 바꾼다. 카드 순서를 바꿨다고 열려 있는 목표 수정 화면이 409가 되면 안 되기 때문이다(§2.4의 "사용자가 고친 필드만 version" 원칙).
- **기준 저장은 목표 version을 올린다:** 명세 §4.4("저장하면 목표의 version이 오른다"). 목표 필드가 바뀌지 않아도 force increment로 올린다. 기준 달성(`achievement`)은 version을 받지도 올리지도 않는다(§2.4).
- **PATCH의 "없음"과 "null" 구분:** 요청 DTO 필드를 `Optional<T>`로 받는다. 필드가 없으면 Java `null`(바꾸지 않음), JSON `null`이면 `Optional.empty()`(값을 지움)다. 연간 목표에서 `null`로 지울 수 있는 것은 `description`뿐이고, `title`·`color`·`status`가 `null`이면 400 `VALIDATION_FAILED`(`REQUIRED`)다. 단위 3의 월간·주간 `color: null`(상속으로 되돌림)도 이 방식을 쓴다. Jackson 3에서 이 구분이 실제로 되는지 테스트로 먼저 확인한다.
- **입력 규칙:** `title`은 앞뒤 공백을 지운 뒤 1~100자. `description`은 최대 2,000자(명세에 없음, `resultSummary`와 같은 상한). `color`는 `^#[0-9A-Fa-f]{6}$`, 저장은 대문자. `targetYear`는 계정 시간대 기준 올해·내년만(그 밖은 400 `VALIDATION_FAILED`/`OUT_OF_RANGE`).
- **일시 매핑:** user-service `User`처럼 `LocalDateTime`(UTC)으로 매핑하고 응답 DTO에서 `Instant`로 바꾼다. DB 기본값이 채우는 `created_at`·`updated_at`은 `insertable=false, updatable=false`이고, 저장 직후 응답에 필요하므로 Hibernate `@Generated`(INSERT·UPDATE 뒤 다시 읽기)를 붙인다. 앱이 직접 넣는 일시(`criteria_confirmed_at`, `criteria_last_revised_at`, `achieved_at`)는 `Clock`에서 얻는다.
- **DB 세션 시간대:** user-service처럼 main에 `spring.datasource.hikari.connection-init-sql=SET time_zone = '+00:00'`를 추가한다(DB 기본값 `CURRENT_TIMESTAMP`를 UTC로). H2 테스트 설정은 user-service 테스트 설정처럼 덮는다.
- **404 details:** `{ "resource": "YEARLY_GOAL", "id": 3 }`, 기준은 `{ "resource": "CRITERION", "id": 1 }`. 다른 사용자 소유·소프트 삭제도 같은 404다.
- **기준 id 검증:** PUT의 `items[].id`나 확정의 `baselineAchievedCriterionIds`에 이 목표의 기준이 아닌 id가 있거나 같은 id가 두 번 나오면 400 `VALIDATION_FAILED`(field `items[i].id` 또는 `baselineAchievedCriterionIds`, reason `INVALID_FORMAT`).
- **검사 순서:** 확정은 404 → 409 `CRITERIA_ALREADY_CONFIRMED` → 409 `VERSION_CONFLICT` → 422 `CRITERIA_WEIGHT_SUM_INVALID`. 개정(PUT)은 404 → 400 → 409 → 422.
- **진행 기록 대상 이름:** `targetTitle`은 `target_type`별로 목표 테이블에서 읽는다. 이번 단위는 `YEARLY_GOAL`만 있지만 단위 3이 월간·주간을 붙일 수 있게 `ProgressLogTitleResolver`(타입별 id 묶음 조회)로 둔다. 삭제된 목표의 기록도 제목과 함께 보여 준다.
- **연간 달성률 기록:** `previous_rate`·`new_rate`는 정수 배점 합을 `DECIMAL(5,2)`로 저장한다(20 → 20.00). `log_date`는 `UserTime.today()`.

### Review Focus (단위 2)

1. **동시 생성으로 한도 초과**: 활성 2개일 때 두 인스턴스가 동시에 생성하면 하나만 성공해야 한다. → Task 2 `concurrentCreatesRespectLimit`, 서버 MySQL E2E로도 확인.
2. **다른 사용자의 목표 id**로 조회·수정·기준 저장·달성 → 404이며 존재 여부를 드러내지 않는다. → 각 Task의 `otherUsersGoalIs404`.
3. **확정 뒤 개정이 기존 달성 여부를 유지**하고, 개정 전후 달성률이 같아도 `CRITERIA_REVISION`을 남긴다. → Task 4 `revisionKeepsAchievementAndAlwaysLogs`.
4. **달성 요청 반복**(같은 값) → 기록이 추가되지 않는다. → Task 5 `sameAchievementIsNoOp`.
5. **기준 저장과 달성이 동시에** 같은 목표에 오면 달성률이 저장된 기준과 어긋나지 않는다(연간 목표 행 잠금). → 서버 MySQL E2E.

### Task 1: 단위 1 minor — 첫 생성 경합 복구 경로를 반드시 타는 테스트

**Files:** Modify `src/test/java/com/aim2day/goal/settings/SettingsConflictTests.java`

- [ ] **Step 1:** `concurrentFirstReadInsideOuterTransactionRecovers`를 고친다. 기존 훅 장치로 **`getOrCreate`의 첫 `findByUserId`가 빈 결과를 돌려준 직후** 다른 스레드(별도 커넥션)가 같은 사용자 행을 INSERT·커밋하게 한다. 그러면 이어지는 INSERT가 유니크 위반을 맞고 재조회 경로를 탄다. 단언: 예외 없음, 반환 행의 id가 다른 스레드가 넣은 행의 id와 같음, 행 수 1, `findByUserId` 호출 2회(재조회 실행).
- [ ] **Step 2:** `./gradlew test --tests '*SettingsConflictTests'` PASS

### Task 2: V2 마이그레이션 + 연간 목표 생성·목록·순서

**Files:**
- Create: `src/main/resources/db/migration/V2__create_yearly_goals.sql`
- Create: `common/lock/UserRowLock.java`, `goals/GoalStatus.java`, `goals/GoalErrorCode.java`
- Create: `goals/yearly/YearlyGoal.java`, `YearlyGoalRepository.java`, `YearlyGoalService.java`, `YearlyGoalController.java`, `dto/CreateYearlyGoalRequest.java`, `dto/ReorderYearlyGoalsRequest.java`, `dto/YearlyGoalResponse.java`, `dto/YearlyGoalListResponse.java`
- Modify: main·test `application.properties`(connection-init-sql)
- Test: `goals/yearly/YearlyGoalApiTests.java`, `goals/yearly/YearlyGoalLimitConcurrencyTests.java`

**Interfaces:**
- Consumes: `SettingsService.userTime(long)`, `VersionGuard`, `CommonErrorCode`
- Produces:
  - `YearlyGoalService.findOwnedActive(long userId, long goalId) -> YearlyGoal` (없음·타인·삭제 → 404 `YEARLY_GOAL`). 단위 3의 월간 목표 생성이 부모 확인에 쓴다.
  - `YearlyGoalResponse.of(YearlyGoal goal, CriteriaSummary criteria, LocalDate today)`: `pathStatus`는 자기 `status`가 `ON_HOLD`/`DROPPED`면 그 값, 아니면 `null`. `isDelayed`는 `targetYear-12-31 < today && status != COMPLETED`. `displayColor == color`.
  - `record CriteriaSummary(String state, Integer achievementRate, Integer initialBaselineRate, Instant confirmedAt, Instant lastRevisedAt)`: 미확정이면 `NOT_CONFIGURED`와 숫자 `null`. 계산은 Task 3에서 채운다.

- [ ] **Step 1: 실패하는 테스트** `YearlyGoalApiTests`(시계 `2026-11-12T03:00:00Z` 고정)
  - `createDefaultsToCurrentYearAndBlueColor`: `{title:"백엔드"}` → 201, `targetYear==2026`, `color=="#3B82F6"`, `displayOrder==0`, `status=="PROGRESS"`, `criteria.state=="NOT_CONFIGURED"`, `criteria.achievementRate==null`, `version==0`, `createdAt`이 `Z`로 끝남
  - `displayOrderAppendsToEnd`: 두 번째 생성 → `displayOrder==1`
  - `fourthActiveGoalIs409`: 3개 뒤 → 409 `GOAL_LIMIT_EXCEEDED`, `details=={year:2026,current:3,max:3}`
  - `limitIsPerYear`: 2026년 3개여도 `targetYear:2027` 생성 201
  - `targetYearOutsideThisAndNextIs400`: 2025, 2028 → 400 `OUT_OF_RANGE`
  - `titleBlankOrTooLongIs400`, `badColorIs400`(`"blue"`, `"#12345"`), `colorStoredUppercase`(`"#3b82f6"` → `"#3B82F6"`)
  - `listSortedByDisplayOrderThenId`: 응답 `{year, activeCount, maxCount:3, items}`
  - `reorderRenumbersFromZeroWithoutVersionBump`: `[c,a,b]` → 목록 순서 c,a,b, `displayOrder` 0·1·2, 각 `version` 그대로
  - `reorderWithMissingOrExtraIdIs422`: 422 `GOAL_ORDER_MISMATCH`, `details.expectedIds`가 활성 id 오름차순
  - `otherUsersGoalNotInListOrReorder`: 남의 목표 id를 순서에 넣으면 422, 남의 목표는 내 목록에 없음
- [ ] **Step 2:** `YearlyGoalLimitConcurrencyTests.concurrentCreatesRespectLimit`: 활성 2개 상태에서 스레드 4개가 동시에 생성 → 성공 정확히 1, 나머지 `GOAL_LIMIT_EXCEEDED`, 활성 수 3
- [ ] **Step 3:** 실패 확인 → 구현 → `./gradlew test` PASS

### Task 3: 연간 목표 상세·수정 + 기준 요약 계산

**Files:**
- Modify: `goals/yearly/YearlyGoalService.java`, `YearlyGoalController.java`
- Create: `goals/yearly/dto/UpdateYearlyGoalRequest.java`(`Optional` 필드), `dto/YearlyGoalDetailResponse.java`
- Create: `goals/criteria/YearlyGoalCriterion.java`, `CriterionRepository.java`, `CriteriaCalculator.java`
- Test: `goals/yearly/YearlyGoalUpdateTests.java`, `goals/criteria/CriteriaCalculatorTests.java`

**Interfaces:**
- Produces: `CriteriaCalculator.achievementRate(List<YearlyGoalCriterion>) -> int`(달성한 기준의 배점 합), `CriteriaCalculator.summary(YearlyGoal, List<YearlyGoalCriterion>) -> CriteriaSummary`

- [ ] **Step 1: 실패하는 테스트**
  - `CriteriaCalculatorTests`: 배점 40(미달성)·10(달성)·50(달성) → 60, 빈 목록 → 0
  - `detailHasEmptyMonthlyGoalsForNow`: `GET /api/goals/yearly/{id}` → `$.data.goal.id`, `$.data.monthlyGoals == []`
  - `patchChangesOnlySentFields`: `{title:"새 제목", version:0}` → 제목만 바뀌고 나머지 유지, `version==1`
  - `patchNullDescriptionClears`, `patchNullTitleIs400`, `patchStaleVersionIs409WithCurrentVersion`
  - `onHoldSetsPathStatus`
  - `pastYearNotCompletedIsDelayed`: 시계 2027-01-02, 2026년 `PROGRESS` → `isDelayed==true`, `COMPLETED`면 false
  - `otherUsersGoalIs404`: GET·PATCH → 404, `details=={resource:"YEARLY_GOAL",id}`
- [ ] **Step 2:** 실패 확인 → 구현(수정은 `VersionGuard.translateConflict`로 감쌈) → PASS

### Task 4: 달성 기준 조회·저장(초안·개정)·확정 + 진행 기록 쓰기

**Files:**
- Create: `goals/criteria/CriteriaService.java`, `CriteriaController.java`, `CriteriaErrorCode.java`, `dto/CriteriaResponse.java`, `dto/SaveCriteriaRequest.java`, `dto/ConfirmCriteriaRequest.java`
- Create: `progress/GoalProgressLog.java`, `ProgressLogRepository.java`, `ProgressLogWriter.java`, `ProgressTargetType.java`, `ProgressChangeType.java`
- Test: `goals/criteria/CriteriaApiTests.java`

**Interfaces:**
- Produces: `ProgressLogWriter.record(long userId, ProgressTargetType type, long targetId, BigDecimal previousRate, BigDecimal newRate, ProgressChangeType changeType, LocalDate logDate, String memo)`: 호출자 트랜잭션 안에서 INSERT만 한다. 단위 3·4의 진행률 변경도 이걸로 남긴다.

- [ ] **Step 1: 실패하는 테스트** `CriteriaApiTests`
  - `getBeforeConfigIsNotConfiguredAndEmpty`: `state=="NOT_CONFIGURED"`, `items==[]`, `totalWeight==0`, `goalVersion==0`
  - `draftSaveSkipsSumCheckAndBumpsGoalVersion`: 배점 30·30 저장 → 200, `totalWeight==60`, `goalVersion==1`
  - `draftReplaceUpdatesAddsAndDeletes`
  - `foreignOrDuplicateCriterionIdIs400`
  - `itemsCountAndRangeValidated`: 0개, 21개, weight 0·101, text 256자 → 400
  - `confirmRequiresSum100`: 합 60 → 422 `CRITERIA_WEIGHT_SUM_INVALID`, `details=={totalWeight:60,required:100}`
  - `confirmStoresBaselineAndLogsInitialObservation`: 40·10·50 중 baseline `[10짜리 id]` → `state=="CONFIRMED"`, `initialBaselineRate==10`, `achievementRate==10`, 그 기준의 `isAchieved`·`isBaselineAchieved` true, `confirmedAt == lastRevisedAt == 2026-11-12T03:00:00Z`, 진행 기록 1행(`YEARLY_GOAL`, prev 0.00, new 10.00, `PROGRESS_UPDATE`, `logDate 2026-11-12`)
  - `confirmTwiceIs409AlreadyConfirmed`, `confirmStaleGoalVersionIs409`
  - `revisionKeepsAchievementAndAlwaysLogs`: 확정 뒤 달성률이 같은 개정 → 기존 달성 유지, 새 항목 미달성, `CRITERIA_REVISION` 1행(prev == new), `lastRevisedAt` 갱신, `confirmedAt` 유지
  - `revisionWithSumNot100Is422`
  - `otherUsersGoalIs404`: GET·PUT·confirm 모두 404
- [ ] **Step 2:** 실패 확인 → 구현(연간 목표 행 `PESSIMISTIC_WRITE` 잠금 → version 검사 → 변경 → force increment, 전체를 `translateConflict`로 감쌈) → PASS

### Task 5: 기준 달성·달성 취소 + 진행 기록 조회

**Files:**
- Modify: `goals/criteria/CriteriaService.java`, `CriteriaController.java`
- Create: `goals/criteria/dto/AchievementRequest.java`, `AchievementResponse.java`
- Create: `progress/ProgressLogController.java`, `ProgressLogTitleResolver.java`, `dto/ProgressLogResponse.java`
- Test: `goals/criteria/AchievementApiTests.java`, `progress/ProgressLogApiTests.java`

- [ ] **Step 1: 실패하는 테스트**
  - `achieveAddsWeightAndLogs`: 확정(달성률 10) 뒤 40짜리 달성 → `{criterionId, isAchieved:true, achievementRate:50}`, 기록 prev 10.00 → new 50.00, memo는 요청값
  - `sameAchievementIsNoOp`: 다시 `achieved:true` → 200, 기록 수 그대로, `achievedAt` 그대로
  - `unachieveSubtractsAndClearsAchievedAt`, `achievementDoesNotBumpGoalVersion`
  - `achievementBeforeConfirmIs422`: 422 `CRITERIA_NOT_CONFIRMED`
  - `criterionOfOtherGoalIs404`: 남의 목표의 기준 id, 내 다른 목표의 기준 id 모두 404 `CRITERION`
  - `memoOver255Is400`
  - `ProgressLogApiTests`: `recentFirstWithTargetTitle`(기록 3개 → `createdAt` 내림차순, 같은 시각이면 id 내림차순, `targetTitle`, `previousRate` 숫자), `limitDefaultsTo10AndRange1To50`(0, 51 → 400 `OUT_OF_RANGE`), `onlyOwnLogs`
- [ ] **Step 2:** 실패 확인 → 구현 → `./gradlew build` PASS

### Task 6: 서버 검증 (오케스트레이터)

- [ ] review 컨테이너(user-service 1, goal-service 2)는 `aim2day_review` DB에 붙인다. V2 체크섬이 바뀌었으면 시험용 DB의 테이블과 Flyway 이력을 비우고 다시 만든다.
- [ ] E2E: 활성 2개 상태에서 두 인스턴스로 동시 생성 4건 → 1건만 성공. 교차 인스턴스 PATCH 경합 → 200과 409(`currentVersion`). 기준 확정 → 달성 → 개정 흐름과 진행 기록. 달성과 개정을 두 인스턴스에서 동시에 20회 → 매번 최종 달성률 = 저장된 기준 중 달성한 배점 합.
- [ ] 정리 → Codex 리뷰 → 커밋 → 푸시 승인 뒤 운영 배포 → `progress.md`

---

## 단위 3: 월간·주간 목표 + 진행률 엔진 + 수치 기록

**범위:** API 명세 §4.5 전체, §4.6(직전 주 요약 `previous-summary`는 단위 4), §4.3 상세의 하위 트리, §4.8 대상 이름(월간·주간). 삭제·복원은 단위 6.
**새 테이블:** `V3__create_monthly_weekly_goals.sql`에 `monthly_goals`, `weekly_goals`, `weekly_goal_numeric_records`(ERD 3.2-6·7, 3.5-12 그대로). `weekly_goal_numeric_records.action_id`는 FK 없이 만들고 UNIQUE만 둔다. FK `actions(id) ON DELETE SET NULL`은 단위 4의 마이그레이션이 `actions` 테이블과 함께 추가한다.

### 이 단위에서 정한 것

- **패키지:** `goals.monthly`, `goals.weekly`, `goals.weekly.record`(수치 기록), `progress.ProgressEngine`.
- **소유권과 숨김:** 월간·주간은 `yearly_goals.user_id`까지 조인해 소유를 확인한다(ERD 5.1-4). 자신이나 조상 중 하나라도 `deleted_at`이 있으면 일반 API에서 404다(§2.5).
- **부모 검사 오류:**
  - 부모가 없음·타인 소유·삭제(조상 삭제 포함) → 422 `GOAL_PARENT_UNAVAILABLE` `{parentLevel, parentId}`
  - 부모 연간 목표의 연도가 올해가 아님 → 422 `GOAL_PARENT_PERIOD_MISMATCH` `{parentStartDate, parentEndDate}`
  - 부모 월간 목표 기간이 이번 주와 하루도 안 겹침 → 같은 422 `GOAL_PARENT_PERIOD_MISMATCH`
- **`pathStatus`:** 자신부터 위로 올라가며 처음 만나는 `ON_HOLD`·`DROPPED`, 없으면 `null`. **`displayColor`:** 자신 → 월간 → 연간 순으로 처음 만나는 `color`. 공통 도우미 `goals.GoalPath`에 둔다.
- **`isDelayed`:** `endDate < today && status != COMPLETED`(§2.7).
- **진행률 엔진** `progress.ProgressEngine`(모든 진행률 변경의 유일한 경로, ERD 결정 16·17):
  - `addNumeric(long weeklyGoalId, BigDecimal delta)`: `UPDATE weekly_goals SET current_value = current_value + :delta WHERE id = :id`
  - `recalcWeekly(long userId, long weeklyGoalId, LocalDate logDate, String memo) -> WeeklyProgress`
    1. 주간 행을 `SELECT ... FOR UPDATE`로 잠그고 다시 읽는다.
    2. 진행률을 계산한다.
       - 체크형은 `WeeklyActionCounter`가 준 (완료, 분모)로 계산한다.
       - 수치형은 `MIN(100, ROUND(current/target*100, 2))`, 음수면 0이다.
    3. 계산한 값이 저장값과 다르면 직접 UPDATE하고 `WEEKLY_GOAL` 기록을 남긴다.
    4. 이어서 부모 월간에 대해 `recalcMonthly`를 부른다.
  - `recalcMonthly(long userId, long monthlyGoalId, LocalDate logDate, String memo) -> MonthlyProgress`
    1. 월간 행을 `FOR UPDATE`로 잠근다.
    2. 삭제되지 않은 하위 주간의 저장된 `progress_rate`와 `weight`로 가중 평균을 내고 HALF_UP 둘째 자리로 맞춘다. 하위가 0개면 0.00이다.
    3. 값이 바뀌면 UPDATE하고 `MONTHLY_GOAL` 기록을 남긴다.
  - **잠금 순서:** users(필요한 경로만) → 주간 → 월간. 월간 두 개를 잠글 때(주간 이동)는 id 오름차순으로 잠근다.
  - `WeeklyActionCounter` 인터페이스: `counts(long weeklyGoalId) -> ActionCounts(int completed, int total)`.
    - 이번 단위의 구현은 행동 테이블이 없어 항상 `(0, 0)`을 준다. 그래서 체크형은 `NO_RECORD`, 저장값 0.00이다.
    - 단위 4가 실제 집계로 바꾼다. 집계 규칙은 완료 행동(삭제 포함) / (미삭제 행동 + 삭제된 완료 행동)이다.
  - **진행 기록:** 저장값이 바뀔 때만 남긴다. memo는 호출자가 준다(예: `주간 목표 추가: {제목}`, `수치 기록: +3`).
- **화면용 `progress`:**
  - 주간 체크형은 연결 행동이 0개면 `{NO_RECORD, null}`이다.
  - 주간 수치형은 항상 `RECORDED`다(목표값이 있으므로 0%도 기록이다).
  - 월간은 하위 주간이 0개면 `NO_RECORD`다.
- **수치 입력 범위:**
  - `targetValue`와 `amount`는 0 초과 99,999,999.99 이하, 소수 둘째 자리까지다. 셋째 자리가 있으면 400 `INVALID_FORMAT`이다.
  - 더하면 `current_value`가 `DECIMAL(10,2)` 상한을 넘는 직접 기록은 400 `OUT_OF_RANGE`다. 상한 검사는 잠근 행을 읽어서 한다.
  - `weight`는 0 초과 999.99 이하다.
- **주간 PATCH:**
  - `measurementType`을 보내고 현재 값과 다르면 422 `GOAL_MEASUREMENT_TYPE_IMMUTABLE`이다. 같으면 무시한다.
  - 체크형에 `targetValue`를 보내면 422 `GOAL_NOT_NUMERIC`이다.
  - `monthlyGoalId` 이동은 대상 월간이 이 주의 기간과 겹치고 같은 사용자의 활성 목표여야 한다. 아니면 422(위 부모 검사와 같다).
  - 이동 뒤에는 이전·새 월간을 모두 다시 계산한다.
- **PATCH 필드 구분:** 단위 2의 setter 방식(`@JsonSetter` + `Optional`)을 그대로 쓴다. 월간·주간의 `color: null`은 "부모 색 상속으로 되돌림"이다.
- **목록 정렬:**
  - 월간: 연간 `displayOrder`, 월간 `id` 순이다.
  - 주간(`?date=`): 월간 정렬과 같은 순서 뒤에 주간 `id` 순이다.
  - 연간 상세 트리: 월간 `startDate`, `id` 순이고 그 안의 주간은 `startDate`, `id` 순이다.

### Review Focus (단위 3)

1. **같은 주간 목표에 두 인스턴스가 동시에 수치 기록** → `current_value`는 합계와 정확히 같고, 월간 진행률은 최종 주간 값으로 계산된다. → 서버 MySQL E2E(동시 20건) + H2 `concurrentNumericRecordsSum`.
2. **같은 월간 아래 두 주간 목표를 동시에 갱신** → 월간 진행률이 두 변경을 모두 반영한다(월간 행 잠금). → 서버 MySQL E2E.
3. **조상 삭제로 숨겨진 목표**(DB에서 연간 `deleted_at`을 직접 채워 재현) → 월간·주간 조회·생성 부모·수치 기록이 404/422다. → Task 2·3 `hiddenByDeletedAncestor`.
4. **주간 목표 수정(제목)이 진행률·현재값을 되돌리지 않음**(ERD 결정 17) → 수치 기록 뒤 오래된 화면 상태로 PATCH해도 `current_value` 유지. → Task 4 `titlePatchDoesNotOverwriteCurrentValue`.
5. **월 경계 주**(11-29~12-05): 11월·12월 월간 둘 다 부모가 될 수 있고, 나중에 다른 쪽으로 옮길 수 있다. → Task 3 `monthBoundaryWeekAcceptsEitherMonth`.

### Task 1: V3 마이그레이션 + 진행률 엔진

**Files:**
- Create: `db/migration/V3__create_monthly_weekly_goals.sql`
- Create: `goals/monthly/MonthlyGoal.java`, `MonthlyGoalRepository.java`; `goals/weekly/WeeklyGoal.java`, `WeeklyGoalRepository.java`, `MeasurementType.java`
- Create: `progress/ProgressEngine.java`, `WeeklyActionCounter.java`, `NoActionCounter.java`(단위 3용 `(0,0)` 구현), `ActionCounts.java`, `ProgressMath.java`
- Test: `progress/ProgressMathTests.java`, `progress/ProgressEngineTests.java`

**Interfaces (Produces):**
- `ProgressMath.weeklyChecklist(int completed, int total) -> BigDecimal`(total 0이면 0.00), `ProgressMath.weeklyNumeric(BigDecimal current, BigDecimal target) -> BigDecimal`, `ProgressMath.monthly(List<WeightedRate>) -> BigDecimal`
- `record WeeklyProgress(long weeklyGoalId, BigDecimal rate, BigDecimal currentValue, Boolean targetReached)`, `record MonthlyProgress(long monthlyGoalId, BigDecimal rate, int weeklyCount)`
- `ProgressEngine.addNumeric`, `recalcWeekly`, `recalcMonthly`(위 규칙). 호출자 트랜잭션 필수(`MANDATORY`).
- 엔티티의 `current_value`·`progress_rate`는 `@Column(updatable = false)`다.

- [ ] **Step 1: 실패하는 테스트**
  - `ProgressMathTests`
    - `checklist 2/3 = 66.67`, `1/3 = 33.33`, `0/0 = 0.00`
    - `numeric 14/10 = 100.00`(상한), `9999.99/10000 = 100.00`이지만 `targetReached`는 false(엔진 테스트에서)
    - `monthly [(100,1),(50,1),(0,2)] = 37.50`
    - `monthly HALF_UP: [(66.67,1),(33.33,2)] = 44.44`
  - `ProgressEngineTests`(H2)
    - `numericDeltaIsAtomicAndRecalculates`: 목표 10, +3 → 주간 30.00, 월간(하위 1개, weight 1) 30.00, 기록 2행(WEEKLY 0→30, MONTHLY 0→30)
    - `noLogWhenRateUnchanged`
    - `entitySaveDoesNotOverwriteProgress`: 엔티티를 읽음 → 엔진으로 +5 → 읽어 둔 엔티티의 제목을 바꿔 save → DB `current_value`는 5, `version`은 1
    - `concurrentNumericRecordsSum`: 스레드 8개가 각 +1 → `current_value == 8`
- [ ] **Step 2:** 실패 확인 → 구현 → PASS

### Task 2: 월간 목표 API + 연간 상세 트리

**Files:**
- Create: `goals/GoalPath.java`, `goals/monthly/MonthlyGoalService.java`, `MonthlyGoalController.java`, `dto/CreateMonthlyGoalRequest.java`, `dto/UpdateMonthlyGoalRequest.java`, `dto/MonthlyGoalResponse.java`, `dto/MonthlyGoalDetailResponse.java`
- Modify: `goals/yearly/YearlyGoalService.detail`, `dto/YearlyGoalDetailResponse.java`(`List<MonthlyGoalWithWeeklies>`), `goals/GoalErrorCode.java`(`GOAL_PARENT_UNAVAILABLE`, `GOAL_PARENT_PERIOD_MISMATCH`), `progress/ProgressLogTitleResolver.java`(월간·주간)
- Test: `goals/monthly/MonthlyGoalApiTests.java`

**Interfaces (Produces):** `MonthlyGoalService.findOwnedVisible(long userId, long monthlyGoalId) -> MonthlyGoal`(조상 포함 숨김이면 404 `MONTHLY_GOAL`), `MonthlyGoalResponse.of(...)`.

- [ ] **Step 1: 실패하는 테스트** `MonthlyGoalApiTests`(시계 2026-11-12T03:00Z, Asia/Seoul)
  - `createUsesCurrentMonth`: → 201, `month=="2026-11"`, `startDate=="2026-11-01"`, `endDate=="2026-11-30"`, `color==null`, `displayColor`==부모 색, `progress.state=="NO_RECORD"`, `weeklyGoalCount==0`
  - `parentOfNextYearIs422PeriodMismatch`: 2027년 연간 아래 생성 → 422 `GOAL_PARENT_PERIOD_MISMATCH`, `details.parentStartDate=="2027-01-01"`
  - `otherUsersParentIs422Unavailable`: `details=={parentLevel:"YEARLY",parentId}`
  - `hiddenByDeletedAncestor`: 연간 `deleted_at` 채움 → 월간 GET 404, 목록에서 빠짐, 그 연간을 부모로 생성 422 `GOAL_PARENT_UNAVAILABLE`
  - `listDefaultsToCurrentMonthAndFiltersByYearly`, `listOrderFollowsYearlyDisplayOrder`
  - `patchColorNullInheritsParent`, `patchStaleVersionIs409`, `onHoldParentGivesPathStatus`
  - `yearlyDetailIncludesMonthlyWithWeeklies`(주간은 Task 3 뒤 채워지는지 Task 3에서 재확인)
  - `progressLogTitleForMonthly`
- [ ] **Step 2:** 실패 확인 → 구현 → PASS

### Task 3: 주간 목표 API

**Files:**
- Create: `goals/weekly/WeeklyGoalService.java`, `WeeklyGoalController.java`, `dto/CreateWeeklyGoalRequest.java`, `dto/UpdateWeeklyGoalRequest.java`, `dto/WeeklyGoalResponse.java`, `dto/WeeklyGoalDetailResponse.java`
- Modify: `goals/GoalErrorCode.java`(`GOAL_NOT_NUMERIC`, `GOAL_MEASUREMENT_TYPE_IMMUTABLE`)
- Test: `goals/weekly/WeeklyGoalApiTests.java`

**Interfaces (Produces):** `WeeklyGoalService.findOwnedVisible(long userId, long weeklyGoalId) -> WeeklyGoal`(404 `WEEKLY_GOAL`), `WeeklyGoalResponse.of(...)`. 단위 4의 행동 연결이 쓴다.

- [ ] **Step 1: 실패하는 테스트** `WeeklyGoalApiTests`
  - `createUsesCurrentWeekFromSettings`: SUNDAY → `2026-11-08..2026-11-14`, MONDAY로 바꾼 뒤 새로 만들면 `2026-11-09..2026-11-15`, 먼저 만든 주간은 그대로
  - `createRecalculatesMonthly`: 수치형 목표에 +5 기록이 있는 주간 W1(50%) 아래에 W2 생성 → 월간 25.00
  - `numericRequiresTargetValue`, `checklistWithTargetValueIs400`, `weightAndTargetRange`(0, 1000, 99999999.999)
  - `monthBoundaryWeekAcceptsEitherMonth`: 월간은 현재 달로만 만들 수 있으므로 `MutableClock`으로 2026-11-30에 11월 월간을 만들고 2026-12-01(화)로 옮겨 12월 월간을 만든다. SUNDAY 주 11-29~12-05 → 두 월간 모두 주간의 부모가 될 수 있다
  - `parentMonthNotOverlappingWeekIs422`: `MutableClock`으로 10월에 월간을 만들고 11월 12일로 옮겨 그 아래 주간 생성 → 422 `GOAL_PARENT_PERIOD_MISMATCH`
  - `checklistWithoutActionsIsNoRecord`: `progress=={NO_RECORD,null}`, `actionCounts=={total:0,completed:0}`, `targetValue==null`
  - `listByDateAndMonthly`: `?date=2026-11-12` 포함, `?date=2026-11-20` 빈 목록, `monthlyGoalId` 필터
  - `patchMeasurementTypeIs422`, `patchTargetValueOnChecklistIs422NotNumeric`, `patchTargetValueRecalculates`(10→20, 현재 5 → 25.00, 월간도 갱신)
  - `moveToOtherMonthlyRecalculatesBoth`: 경계 주에서 11월 → 12월 이동 → 11월 월간 `NO_RECORD`, 12월 월간에 반영
  - `hiddenByDeletedAncestor`, `otherUsersWeeklyIs404`
- [ ] **Step 2:** 실패 확인 → 구현 → PASS

### Task 4: 수치 기록 API

**Files:**
- Create: `goals/weekly/record/NumericRecord.java`, `NumericRecordRepository.java`, `NumericRecordService.java`, `NumericRecordController.java`, `RecordSource.java`, `RecordErrorCode.java`(`RECORD_NOT_DELETABLE`), dto 3개
- Test: `goals/weekly/record/NumericRecordApiTests.java`

- [ ] **Step 1: 실패하는 테스트** `NumericRecordApiTests`
  - `directInputAddsAndReturnsRecordAndGoal`: +3 → 201, `record.source=="DIRECT_INPUT"`, `record.action==null`, `weeklyGoal.currentValue==3.00`, `progress.rate==30.00`
  - `overTargetKeepsRawValue`: +14 → `currentValue==14.00`, `rate==100.00`, `isTargetReached==true`
  - `targetReachedUsesRawCompare`: 목표 10000, +9999.99 → `rate==100.00`, `isTargetReached==false`
  - `checklistGoalIs422NotNumeric`, `amountZeroOrNegativeIs400`, `amountThreeDecimalsIs400`, `sumOverflowIs400`
  - `deleteDirectRecordSubtracts`: 삭제 → `currentValue` 원복, 진행률 재계산, 기록 행 없음
  - `deleteActionCompletionRecordIs422`: DB에 `ACTION_COMPLETION` 행을 직접 넣고(action_id 임의 값) 삭제 → 422 `RECORD_NOT_DELETABLE`
  - `recordOfOtherGoalIs404`
  - `listNewestFirst`
  - `titlePatchDoesNotOverwriteCurrentValue`: 수치 기록 전에 읽은 version으로 제목 PATCH → 200, `currentValue` 유지
- [ ] **Step 2:** 실패 확인 → 구현 → `./gradlew build` PASS

### Task 5: 서버 검증 (오케스트레이터)

- [ ] `aim2day_review`에서 E2E
  - 같은 주간에 두 인스턴스로 동시 +1 20건 → `current_value` 20.00
  - 같은 월간의 두 주간에 동시 기록 → 월간 = 두 주간의 가중 평균
  - 경계 주 이동
  - 연간 상세 트리
  - 진행 기록 대상 이름
- [ ] 정리 → Codex 리뷰 → 커밋 → 푸시 → 운영 배포 → `progress.md`

---

## 단위 4: 행동 + 직전 주 요약

**범위:** API 명세 §4.9 전체(사분면 `placement`는 단위 5 전까지 항상 `UNCLASSIFIED`), §4.6 `previous-summary`, 주간 상세의 `actions`·`actionCounts`, 체크형 진행률 집계.
**새 테이블:** `V4__create_actions.sql`
- `actions`(ERD 3.3-8)
- `weekly_goal_numeric_records.action_id`에 FK `actions(id) ON DELETE SET NULL` 추가

### 이 단위에서 정한 것

- **패키지:** `actions`(엔티티·서비스·컨트롤러), `actions.dto`, `ActionErrorCode`(`ACTION_WEEKLY_GOAL_REQUIRED`, `ACTION_WEEKLY_GOAL_AMBIGUOUS`, `ACTION_FUTURE_COMPLETION`, `ACTION_DATE_OUT_OF_RANGE`).
- **진행률 집계:** 체크형 집계를 `WeeklyActionCounter`의 실제 구현으로 바꾼다.
  - 완료 = 연결 행동 중 `is_completed`인 것(삭제 포함)
  - 분모 = `deleted_at IS NULL OR is_completed`
  - 단위 3의 `NoActionCounter`는 지운다.
- **잠금 순서:** users → 행동 → 주간(id 오름차순) → 월간(id 오름차순).
  - 진행 기록을 남길 수 있는 모든 쓰기(생성·수정·완료·삭제)가 이 순서를 따른다.
  - users를 먼저 잠그는 이유는 진행 기록 INSERT의 FK 공유 잠금이다(단위 2에서 배움).
- **숨김:** 행동 자신이 삭제됐거나 경로의 목표가 삭제(조상 포함)된 **미완료** 행동은 목록·조회에서 빠진다(§4.9 GET).
  - 경로가 삭제된 행동은 완료 여부와 관계없이 수정·완료·삭제 요청이 404 `ACTION`이다(가정: 숨은 부모의 진행률을 바꾸지 않기 위해).
- **생성 규칙 순서:**
  1. 400 입력 검증
  2. `GOAL`이면 `performedDate > 현재 주 끝` → 422 `ACTION_DATE_OUT_OF_RANGE`
  3. 연결 대상 결정
     - `weeklyGoalId`가 있으면 그것을 쓴다. 기간 포함은 묻지 않는다. 함께 온 `monthlyGoalId`가 그 주간의 부모가 아니면 400이다.
     - `weeklyGoalId`가 없고 `monthlyGoalId`만 있으면 수행일을 포함하는 주간을 찾는다. 0개면 `ACTION_WEEKLY_GOAL_REQUIRED`(`details`에 `monthlyGoalId`, `performedDate`), 여러 개면 `ACTION_WEEKLY_GOAL_AMBIGUOUS`(후보 `id`·`title`·`startDate`·`endDate`)다.
     - 둘 다 없으면 400 `VALIDATION_FAILED`다.
  4. 부모가 숨김·타인 소유 → 422 `GOAL_PARENT_UNAVAILABLE`
  5. `contributionValue`를 체크형에 주면 → 422 `GOAL_NOT_NUMERIC`
- **`MAINTENANCE`:** `monthlyGoalId`·`weeklyGoalId`·`contributionValue` 중 하나라도 오면 400이다. 날짜 제한은 없다.
- **입력 범위:**
  - `title`: 앞뒤 공백 제거 후 1~255자
  - `resultSummary`: 최대 2,000자
  - `contributionValue`: 0 초과 99,999,999.99 이하, 소수 둘째 자리까지
  - `memo`: 최대 255자
- **완료(`PUT .../completion`):**
  - 같은 상태면 아무것도 하지 않고 200을 준다.
  - 완료 시
    - 수행일이 오늘보다 뒤면 422 `ACTION_FUTURE_COMPLETION`(`details`에 `today`, `performedDate`)이다.
    - 수치형이고 기여량이 있으면 `ACTION_COMPLETION` 기록을 넣고 `addNumeric(+v)`를 한다.
    - 그다음 `recalcWeekly`를 부른다.
  - 취소 시 기록을 지우고 `addNumeric(-amount)` 뒤 재계산한다.
  - 진행 기록 memo는 요청값을 쓴다. 없으면 완료는 `행동 완료: {제목}`, 취소는 `행동 완료 취소: {제목}`이다.
  - 응답은 `{action, weeklyGoal{id, progress, currentValue, isTargetReached}, monthlyGoal{id, progress}}`이고, `MAINTENANCE`면 두 목표가 `null`이다.
  - 행동 행을 `FOR UPDATE`로 잠근 뒤 상태를 다시 읽는다. 같은 행동을 두 인스턴스가 동시에 완료해도 한 번만 반영한다(UNIQUE `action_id`는 이중 안전망).
- **수정(PATCH):** 바꿀 수 있는 필드는 §4.9 목록이다. `type`을 보내 다른 값이면 400이다. 아래 규칙을 지킨다.
  - **수행일**
    - 완료된 행동의 수행일을 미래로 바꾸면 `ACTION_FUTURE_COMPLETION`이다.
    - `GOAL` 행동은 현재 주 끝보다 뒤로 바꾸면 `ACTION_DATE_OUT_OF_RANGE`다.
    - 지난 주 연결을 유지한 채 오늘로 옮기는 것은 허용한다.
  - **주간 이동(`weeklyGoalId`)**
    - 이전·새 주간을 id 오름차순으로 잠근다.
    - 완료된 수치 기여가 있으면 이전 주간에서 `-amount`, 새 주간에서 `+amount`를 하고 기록의 `weekly_goal_id`를 옮긴다.
    - 새 주간이 체크형인데 같은 요청에서 `contributionValue`를 `null`로 보내지 않으면 `GOAL_NOT_NUMERIC`이다.
    - 이전·새 경로를 모두 다시 계산한다.
  - **완료된 행동의 `contributionValue`**
    - 값을 바꾸면 기록 `amount`를 고치고 차이만큼 `addNumeric`한다.
    - `null`로 바꾸면 기록을 지우고 빼낸다.
    - `null`에서 값으로 바꾸면 기록을 만들고 더한다.
  - 진행률 영향이 있으면 재계산한다. 전체를 `translateConflict`로 감싼다.
- **삭제:** 소프트 삭제다. 미완료면 체크형 분모에서 빠지므로 재계산하고, 완료면 숨기기만 한다.
- **목록(`GET /api/actions`)**
  - 정렬(§4.9 표): 이번 단위에서는 모두 미분류라 `IMPORTANCE_URGENCY`·`IMPORTANCE`·`URGENCY`가 `id` 오름차순과 같다. 정렬기는 `placement.displayed`를 쓰는 형태로 미리 만든다.
  - `summary`: 그날 목록의 `GOAL`·`MAINTENANCE`별 전체·완료 수
  - `classification`: 이번 단위에서는 `unclassified`=그날 행동 수, 나머지 0, `confirmedToday=false`
  - `pastIncomplete`: `date`가 오늘일 때만 채운다. 수행일이 오늘보다 앞선 미완료 행동이며 숨김 규칙을 적용한다. 수행일 오름차순, 같은 날이면 `id` 오름차순이다.
  - `daysUntilDue = dueDate - today`(없으면 `null`)
  - `goalPath`, `pathStatus`, `displayColor`는 연결 주간의 경로로 만든다. `MAINTENANCE`는 `goalPath=null`, `displayColor`는 고정 회색 `#9CA3AF`(가정)이다.
- **직전 주 요약:**
  - 기간은 현재 주 시작 −7일 ~ −1일이다.
  - 그 기간과 하루라도 겹치는 숨김 아닌 주간 목표를 넣는다.
  - 각 주간의 `completedActions`·`remainingActions`는 연결된 미삭제 행동이며, 수행일·`id` 오름차순으로 정렬한다.

### Review Focus (단위 4)

1. **같은 행동을 두 인스턴스가 동시에 완료** → 수치 기록 1행, `current_value`는 한 번만 더해진다. → 서버 E2E와 H2 `concurrentSameActionCompletion`.
2. **같은 주간의 서로 다른 행동 동시 완료** → 기여량 합이 정확하다(ERD 결정 16 예시 2+1=3). → 서버 E2E.
3. **완료↔취소 반복** → `current_value`가 원래대로 돌아오고, 진행 기록의 이전·다음 값이 이어진다. → 서버 E2E.
4. **완료된 수치 행동을 다른 주간으로 이동** → 두 주간의 `current_value`와 두 월간 진행률이 맞다. → Task 3 테스트.
5. **수행일 경계:** 미래 GOAL 날짜는 차단하고, 지난 주 행동을 오늘로 옮기는 것은 허용하며, 미래 날짜 완료는 차단한다. → Task 2·3 테스트.

### Task 1: V4 마이그레이션 + 행동 엔티티 + 체크형 집계

- 파일:
  - `db/migration/V4__create_actions.sql`
  - `actions/Action.java`, `ActionRepository.java`, `ActionType.java`
  - `progress/JpaWeeklyActionCounter.java`(`NoActionCounter` 삭제)
- 테스트 `ActionCounterTests`
  - 완료 2·미완료 1 → 66.67
  - 미완료 1을 삭제 → 100.00
  - 완료 1을 삭제 → 분모·분자 유지

### Task 2: 행동 생성·목록·조회 + 직전 주 요약 + 주간 상세 `actions`

- 테스트 `ActionCreateApiTests`
  - 연결 규칙: 0·1·여러 개 후보, `weeklyGoalId` 직접 지정, 둘 다 없음 400
  - 현재 주 이후 GOAL → `ACTION_DATE_OUT_OF_RANGE`
  - 미래 MAINTENANCE는 허용
  - 체크형 생성 시 재계산(0/1 → `RECORDED` 0.00)
  - `contributionValue` 체크형 422
  - 숨김 부모 422
- 테스트 `ActionListApiTests`
  - `summary`
  - `pastIncomplete`는 오늘만 채우고, 다른 날짜면 `null`
  - 정렬 4종의 순서(현재는 `id` 순, `GROUP`은 `GOAL` 먼저)
  - `daysUntilDue`
  - 숨김 규칙
- 테스트 `PreviousSummaryApiTests`
- 주간 상세의 `actions`와 `actionCounts`를 확인한다.

### Task 3: 완료·수정·삭제

- 테스트 `ActionCompletionApiTests`
  - 완료 시 기록·가산·재계산·진행 기록(memo 기본값)
  - 같은 상태 반복은 아무것도 바꾸지 않음
  - 취소 시 차감
  - 미래 수행일 422
  - `MAINTENANCE` 응답의 목표가 `null`
  - `concurrentSameActionCompletion`
- 테스트 `ActionUpdateApiTests`
  - 기여량 변경 2→5(실제값 5), `null`(삭제·차감), `null`→3(생성·가산)
  - 주간 이동(수치→수치, 수치→체크형은 `contributionValue: null` 필수)
  - 완료 행동의 수행일을 미래로 → 422
  - GOAL 수행일을 현재 주 뒤로 → 422
  - 지난 주 행동을 오늘로 옮기기 허용
  - `type` 변경 400
  - stale version 409와 `currentVersion`
- 테스트 `ActionDeleteApiTests`
  - 미완료 삭제 시 재계산
  - 완료 삭제 시 진행률·기록 유지

### Task 4: 서버 검증 (오케스트레이터)

- `tools/e2e/unit4_actions.py` 시나리오
  - Review Focus 1~3
  - 두 인스턴스 교차로 완료·취소 20회
  - 진행 기록 연속성
