# Aim2Day 서비스 공유 계약

이 문서는 `user-service`와 `goal-service`(3개 인스턴스), 인프라가 **함께 지켜야 하는 약속**을 정한다. 한쪽만 바꾸면 다른 쪽이 깨지는 항목이다. 이 문서는 오케스트레이터만 고치며, 고칠 때는 영향을 받는 서비스를 함께 고친다.

API 경로·응답 형식·오류 코드·세션 정책 자체는 [API 명세서](05_api-specification.md)가 기준이다. 이 문서는 명세서에 없는 구현 수준의 약속만 적고, 명세서와 겹치는 내용은 해당 절을 가리킨다.

## 1. 컨테이너·네트워크·볼륨 이름

이름은 소문자 kebab-case, 접두사 `aim2day-`를 쓴다. 밑줄은 쓰지 않는다. 밑줄이 들어간 호스트명을 Host 헤더로 받으면 Tomcat이 400을 반환하기 때문이다.

| 구성 요소 | 이름 | 이미지 | 컨테이너 내부 포트 | 호스트 공개 |
|---|---|---|---|---|
| 역방향 프록시 | `aim2day-proxy` | nginx:alpine + 설정 | 80 | **80** (유일한 공개 포트) |
| 프론트엔드 | `aim2day-frontend` | nginx:alpine + 정적 파일 | 80 | 없음 |
| 인증 서비스 | `aim2day-user-service` | `aim2day-user-service` | 8080 | 없음 |
| 목표 서비스 | `aim2day-goal-service-1`~`3` | `aim2day-goal-service` (같은 이미지) | 8080 | 없음 |
| 세션 저장소 | `aim2day-redis` | `redis:8-alpine` | 6379 | 없음 |
| DB | `aim2day-mysql` | `mysql:8.4` | 3306 | 없음 |
| 네트워크 | `aim2day-net` | 사용자 정의 bridge | - | - |
| DB 볼륨 | `aim2day-mysql-data` | `/var/lib/mysql`에 마운트 | - | - |

- 각 컨테이너의 `--hostname`은 컨테이너 이름과 같게 준다. `goal-service`의 `X-Instance-Id` 값은 이 호스트명이다(API 명세 §2.6).
- `goal-service` 컨테이너 3개는 같은 네트워크 별칭 `goal-services`(`--network-alias goal-services`)를 가진다. Docker DNS가 이 이름을 3개 IP로 풀고, 프록시는 이 별칭 하나로 3대에 RR 분산한다. Nginx는 이름을 시작할 때 한 번만 풀므로 프록시는 goal-service 3개가 뜬 뒤 마지막에 실행하고, goal 컨테이너를 다시 만들면 `nginx -s reload`한다.
- Redis는 세션과 만료되는 토큰만 저장하므로 볼륨을 두지 않는다. 재시작하면 사용자는 다시 로그인한다.
- 이미지는 `linux/amd64`로 빌드한다(`docker build --platform linux/amd64`). 플랫폼은 Dockerfile이 아니라 빌드 명령에서 지정한다.

## 2. 서비스 실행 환경 변수

접속 정보는 이미지와 `application.properties`에 넣지 않고 `docker run -e` 또는 `--env-file`로 주입한다. Spring Boot의 relaxed binding으로 아래 변수가 설정 키에 연결된다. 과제 제출물이므로 실제 값은 `docs/aim2day/07_container-commands.md`의 `docker run -e` 명령에 그대로 적고 커밋한다(사용자 결정, 2026-11-16). 이 값은 이 과제 전용이며 다른 곳에 재사용하지 않는다. 출시할 때는 DB 안에서 비밀번호를 바꾸고(`ALTER USER`) 저장소 밖으로 옮긴다.

| 환경 변수 | 설정 키 | 값 |
|---|---|---|
| `SPRING_DATASOURCE_URL` | `spring.datasource.url` | `jdbc:mysql://aim2day-mysql:3306/aim2day` |
| `SPRING_DATASOURCE_USERNAME` | `spring.datasource.username` | `aim2day_app` (MySQL 일반 계정, root 금지) |
| `SPRING_DATASOURCE_PASSWORD` | `spring.datasource.password` | MySQL `MYSQL_PASSWORD`와 같은 값 |
| `SPRING_DATA_REDIS_HOST` | `spring.data.redis.host` | `aim2day-redis` |

MySQL 컨테이너는 `MYSQL_ROOT_PASSWORD`, `MYSQL_DATABASE=aim2day`, `MYSQL_USER=aim2day_app`, `MYSQL_PASSWORD`를 받는다. 이 값들은 볼륨이 비어 있는 첫 실행 때만 적용된다.

## 3. 데이터베이스 공유

두 서비스는 같은 DB `aim2day`를 쓴다(ERD §5).

- **스키마는 Flyway만 만든다.** 각 서비스는 `spring.jpa.hibernate.ddl-auto=validate`(또는 `none`)로 두고 Hibernate가 테이블을 만들거나 바꾸지 않게 한다.
- **Flyway 이력 테이블을 서비스별로 나눈다.** 기본 이름(`flyway_schema_history`)을 같이 쓰면 서로의 마이그레이션 이력을 자기 것으로 읽어 충돌한다.

| 서비스 | `spring.flyway.table` | 만드는 테이블 |
|---|---|---|
| `user-service` | `flyway_user_service_history` | `users` |
| `goal-service` | `flyway_goal_service_history` | `users`를 제외한 ERD의 모든 테이블 (`user_settings` 포함, ERD 결정 14) |

- 두 서비스 모두 `spring.flyway.baseline-on-migrate=true`, `spring.flyway.baseline-version=0`을 둔다. 다른 서비스의 테이블이 먼저 있는 DB에서 자기 이력 테이블이 없으면 Flyway가 시작을 거부하기 때문이다. baseline 버전이 기본값 1이면 첫 마이그레이션 V1을 건너뛰므로 0으로 둔다.
- `goal-service` 테이블은 `users(id)`를 외래 키로 참조한다. 따라서 **`user-service`가 먼저 기동해 `users`를 만든 뒤** `goal-service`를 기동한다. `goal-service`는 `users`에 쓰지 않는다.

## 4. Redis 세션

정책(쿠키 이름 `SESSION`, 인덱스형 저장소, 30분 만료, 쿠키 속성)은 API 명세 §2.2를 따른다. 아래는 두 서비스가 같은 세션을 읽기 위한 구현 약속이다.

| 항목 | 값 | 이유 |
|---|---|---|
| 키 네임스페이스 | `spring.session.redis.namespace=spring:session` (두 서비스 모두 명시) | 다르면 서로의 세션을 찾지 못한다 |
| 저장소 | `@EnableRedisIndexedHttpSession` (두 서비스 모두) | API 명세 §2.2 |
| 직렬화 | Spring Session 기본값(JDK 직렬화)을 바꾸지 않는다 | 한쪽만 JSON으로 바꾸면 다른 쪽이 읽지 못한다 |
| 사용자 식별 속성 | `userId` = `java.lang.Long` | API 명세 §2.2 |
| 인덱스 속성 | `FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME` = `userId`의 문자열 | 비밀번호 재설정 때 사용자의 모든 세션 삭제 |

- **세션에는 JDK 표준 타입(`Long`, `String`)만 넣는다.** 어느 서비스에만 있는 클래스(예: `com.aim2day.user.…`의 DTO, 커스텀 `UserDetails`)를 넣으면 JDK 직렬화로 저장되어 다른 서비스가 역직렬화할 때 `ClassNotFoundException`이 난다.
- **인증은 두 서비스 모두 `userId` 속성으로 판단한다.** 각 서비스의 보안 필터가 요청마다 세션의 `userId`를 읽어 인증 객체를 만든다. Spring Security의 `SPRING_SECURITY_CONTEXT`를 세션에 저장해 공유하는 방식은 쓰지 않는다. 그 안의 principal 타입이 서비스 간 계약이 되어 깨지기 쉽기 때문이다.
- `userId`가 `Long`이 아니면(예: `Integer`) 인증하지 않는다.
- **세션 고정 방어 오작동 방지.** 두 서비스 모두 `sessionManagement`를 끄고, SecurityContext 저장소는 `RequestAttributeSecurityContextRepository`, `requestCache`도 끈다. 우리 필터가 만든 인증은 저장소에 저장되지 않으므로 `SessionManagementFilter`가 켜져 있으면 요청마다 "새 로그인"으로 보고 세션 ID를 바꾼다. 그러면 SESSION 쿠키가 계속 무효화되고, 다른 인스턴스로 동시에 간 요청이 옛 ID로 401이 난다. 로그인 때 세션 ID 재발급은 `user-service`의 로그인 처리가 직접 한다.
- 세션 쿠키는 `CookieSerializer`로 이름 `SESSION`, `Path=/`, `HttpOnly`, `SameSite=Lax`를 두 서비스가 같게 둔다. 쿠키 값의 Base64 인코딩 등 나머지는 기본값을 바꾸지 않는다.
- CSRF는 `CookieCsrfTokenRepository.withHttpOnlyFalse()`(쿠키 `XSRF-TOKEN`, `Path=/`, 헤더 `X-XSRF-TOKEN`)와 `csrf.spa()`를 두 서비스가 같게 쓴다. `spa()`가 없으면 기본 처리기가 마스킹된 토큰을 기대해, 쿠키 값을 그대로 헤더에 넣은 요청이 403이 된다.
- 로그인(세션 생성·`userId` 저장)과 로그아웃(세션 무효화)은 `user-service`만 한다. `goal-service`는 세션을 읽기만 하고 새로 만들지 않는다.
- Redis의 `auth:*` 키(인증·재설정 토큰, 메일 재요청 제한)는 `user-service`만 쓴다(API 명세 §3.1).

## 5. 공통 응답 형식

형식과 오류 코드는 API 명세 §2.1·§2.8이 기준이다.

- 공유 라이브러리를 두지 않는다. 두 서비스가 같은 JSON 모양의 응답 클래스와 전역 예외 처리기를 각자 가진다.
- Spring Security가 직접 내보내는 응답도 같은 형식으로 맞춘다. 인증 실패(`AuthenticationEntryPoint`)는 401 `UNAUTHENTICATED`, CSRF 실패(`AccessDeniedHandler`)는 403 `CSRF_TOKEN_INVALID`를 `{code, message, details}` 본문으로 반환한다. 기본 설정을 그대로 두면 빈 본문이나 HTML이 나간다.
- CSRF가 아닌 접근 거부(`AccessDeniedException`)는 두 서비스 모두 로그를 남기고 500 `INTERNAL_ERROR`로 응답한다. 역할 기반 권한이 없으므로 이 경로는 보안 설정 오류일 때만 생긴다.
- 405 응답은 공통 본문(`MALFORMED_REQUEST`)을 쓰되 `Allow` 헤더를 유지한다. 명세에 코드가 없는 다른 Spring MVC 4xx도 원래 상태를 유지한다(500으로 바꾸지 않는다).
- `/error`(서블릿 오류 디스패치)도 공통 오류 본문으로 응답하고 내부 예외 문구를 싣지 않는다.
- **버전 충돌의 `currentVersion`은 도메인 서비스가 채운다.** 공통 처리기는 어느 리소스인지 모르므로 `OptimisticLockingFailureException`을 409 `VERSION_CONFLICT`·`details: null`로만 처리하는 안전망이다. `version`이 있는 수정 API는 서비스 계층에서 충돌(사전 비교와 커밋 시점 충돌 모두)을 잡아 현재 버전을 다시 읽고 `details.currentVersion`을 채워 던진다(API 명세 §2.8). 해당 API를 구현할 때 동시 수정 테스트로 확인한다.
- 일시는 ISO-8601 UTC(`Z`)로 직렬화한다(API 명세 §2.3). JVM, Jackson, Hibernate(`hibernate.jdbc.time_zone`)의 시간대를 UTC로 고정한다.

## 6. 프록시 경로

- 경로 접두사와 담당 서비스는 API 명세 §1.1을 따른다. `/api/auth/**`·`/api/users/**`는 `user-service`, 나머지 `/api/**`는 별칭 `goal-services:8080`(3대, RR), 그 밖의 경로는 `frontend`다.
- 프록시는 **경로를 바꾸지 않고** 그대로 넘긴다. 따라서 서비스의 컨트롤러는 `/api/...` 전체 경로로 매핑한다.
- 프록시는 `Host`, `X-Forwarded-For`, `X-Forwarded-Proto`를 넘긴다. `Host`를 넘기지 않으면 upstream 이름이 Host 헤더로 전달된다.
- 프론트엔드와 API가 같은 출처이므로 CORS를 열지 않는다(API 명세 §2.2).

## 7. API 문서(Swagger)

springdoc-openapi로 각 서비스가 자기 API 문서를 내고, 프록시를 거쳐 공개한다. 두 서비스의 문서 경로가 겹치지 않도록 서비스별 접두사를 둔다. 프록시는 경로를 바꾸지 않으므로 서비스가 이 경로를 그대로 서빙한다.

| 서비스 | 접두사 | Swagger UI | OpenAPI JSON | 프록시 전달 |
|---|---|---|---|---|
| `user-service` | `/docs/user/` | `/docs/user/swagger-ui.html` | `/docs/user/v3/api-docs` | `aim2day-user-service:8080` |
| `goal-service` | `/docs/goal/` | `/docs/goal/swagger-ui.html` | `/docs/goal/v3/api-docs` | `goal-services:8080`(RR) |

- 문서 경로는 인증 없이 허용한다. Swagger UI에서 상태 변경 요청을 보낼 수 있도록 CSRF 쿠키를 헤더로 넣는 설정을 켠다.
