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
2. 동시성·잠금이 걸린 단위(2·3·4·6)는 **실제 MySQL 통합 테스트**를 둔다. `IT_MYSQL_URL` 환경 변수가 있을 때만 실행하고(user-service의 `IT_REDIS_HOST` 방식과 같음), 서버의 JDK 컨테이너에서 돌린다. H2는 `FOR UPDATE SKIP LOCKED`·행 잠금 동작을 MySQL과 같게 재현하지 못한다.
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
