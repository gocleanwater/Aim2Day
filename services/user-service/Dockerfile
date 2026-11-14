# ===== 1단계: 빌드 =====
# FROM: 베이스 이미지를 고른다. JDK(컴파일러 포함)가 필요한 빌드 단계라 jdk 이미지를 쓴다.
# "AS build"는 이 단계에 이름을 붙여 아래 2단계에서 결과물을 꺼내 쓰기 위함이다.
FROM eclipse-temurin:21-jdk AS build

# WORKDIR: 이후 명령이 실행될 작업 디렉터리(없으면 생성).
WORKDIR /workspace

# 의존성 캐시를 위해 빌드 설정 파일만 먼저 복사한다.
# 소스가 바뀌어도 이 파일들이 같으면 아래 의존성 다운로드 레이어를 캐시에서 재사용한다.
COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle

# Windows에서 체크아웃하면 gradlew에 CRLF가 섞이거나 실행 권한이 없을 수 있다.
# sed로 CR 문자를 제거하고 chmod로 실행 권한을 부여해 "bad interpreter" 오류를 막는다.
RUN sed -i 's/\r$//' gradlew && chmod +x gradlew

# RUN: 빌드 중 명령을 실행하고 결과를 레이어로 저장한다.
# 소스 없이 Gradle 배포판과 의존성을 미리 받아 캐시 레이어로 만든다.
# (소스가 없어 실패할 수 있는 작업이라 || true로 무시; 다운로드가 목적이다.)
RUN ./gradlew --no-daemon dependencies > /dev/null || true

# 소스는 자주 바뀌므로 가장 마지막에 복사한다(위 캐시 레이어가 무효화되지 않도록).
COPY src ./src

# 테스트는 외부 MySQL/Redis가 필요할 수 있으므로 이미지 빌드에서는 제외(-x test)한다.
# 테스트는 로컬/CI에서 ./gradlew build 로 따로 돌린다.
RUN ./gradlew --no-daemon bootJar -x test

# ===== 2단계: 실행 =====
# 실행에는 컴파일러가 필요 없어 JRE만 쓰면 이미지가 작아진다.
# Ubuntu 기반이라 `docker exec -it ... bash`가 동작한다(alpine은 bash가 없어 사용하지 않음).
FROM eclipse-temurin:21-jre

WORKDIR /app

# root로 실행하면 컨테이너가 뚫렸을 때 피해가 커지므로 일반 사용자를 만들어 쓴다.
RUN groupadd --system app && useradd --system --gid app --no-create-home app

# COPY --from: 1단계(build)에서 만든 실행 가능 jar만 가져온다.
# build.gradle에서 plain jar 생성을 껐으므로 build/libs에는 실행 가능한 jar 하나만 있다.
COPY --from=build --chown=app:app /workspace/build/libs/*.jar /app/app.jar

# USER: 이후 명령과 컨테이너 실행 프로세스의 사용자를 root가 아닌 app으로 지정.
USER app

# EXPOSE: 컨테이너가 8080 포트를 쓴다는 문서 역할(실제 공개는 docker run -p 로 한다).
EXPOSE 8080

# ENTRYPOINT: 컨테이너 시작 시 실행할 명령. 배열(exec) 형식이라 java가 PID 1이 되어
# docker stop 시 SIGTERM을 직접 받아 정상 종료(graceful shutdown)된다.
# DB/Redis 접속 정보는 docker run -e 환경 변수로 주입한다.
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
