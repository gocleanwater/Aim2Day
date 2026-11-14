# ============================================================
# 1단계: 빌드 단계 (JDK 포함). 결과물인 jar만 2단계로 가져가고 이 단계는 버려진다.
# ============================================================
# FROM: 베이스 이미지 지정. "AS build"는 이 단계에 붙인 이름(뒤에서 COPY --from으로 참조).
FROM eclipse-temurin:21-jdk AS build

# WORKDIR: 이후 명령이 실행될 작업 디렉터리. 없으면 만들어 준다.
WORKDIR /workspace

# 의존성 캐시를 위한 레이어 순서: 자주 안 바뀌는 gradle 관련 파일을 먼저 복사한다.
# 소스만 바뀌면 이 레이어와 아래 의존성 다운로드 레이어가 캐시되어 빌드가 빨라진다.
COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle

# Windows에서 체크아웃하면 gradlew에 CRLF 줄바꿈이나 실행 권한 누락이 생길 수 있다.
# sed로 \r을 제거하고 chmod로 실행 권한을 부여해 어느 환경에서도 동작하게 한다.
RUN sed -i 's/\r$//' gradlew && chmod +x gradlew

# 소스 복사 전에 의존성만 미리 내려받아 레이어로 캐시한다(실패해도 빌드는 계속).
RUN ./gradlew dependencies --no-daemon > /dev/null 2>&1 || true

# 소스는 가장 자주 바뀌므로 마지막에 복사한다.
COPY src ./src

# bootJar: 실행 가능한 fat jar 생성. 테스트는 CI/로컬 ./gradlew build에서 하므로 이미지 빌드에서는 제외(-x test).
RUN ./gradlew bootJar -x test --no-daemon

# ============================================================
# 2단계: 실행 단계 (JRE만 포함해 이미지가 작다).
# ============================================================
# Ubuntu 기반 JRE 이미지. alpine은 쓰지 않는다: 채점 시 `docker exec -it <컨테이너> bash`가 되어야 하므로 bash가 필요하다.
FROM eclipse-temurin:21-jre

WORKDIR /app

# root로 실행하면 컨테이너가 뚫렸을 때 피해가 크다. 권한 없는 전용 계정을 만든다.
RUN groupadd --system app && useradd --system --gid app --no-create-home app

# 1단계에서 만든 jar만 가져온다. --chown으로 파일 소유자를 app 계정으로 지정한다.
# build.gradle에서 plain jar 생성을 껐으므로 build/libs에는 실행 가능한 jar 하나만 있다.
COPY --from=build --chown=app:app /workspace/build/libs/*.jar /app/app.jar

# 이후 명령과 컨테이너 실행을 root가 아닌 app 계정으로 한다.
USER app

# EXPOSE: 컨테이너가 8080 포트를 쓴다는 문서화용 선언. 실제 공개는 docker run -p 또는 같은 네트워크 내 접근으로 한다.
EXPOSE 8080

# 같은 이미지로 컨테이너 3개(goal-service-1~3)를 띄운다. 그래서 DB 주소·비밀번호·Redis 주소 같은
# 인스턴스/환경별 값은 이미지에 넣지 않고, docker run -e (환경 변수)로 실행 시점에 주입한다.
# X-Instance-Id에 쓰는 호스트명도 컨테이너마다 Docker가 다르게 부여하므로 이미지와 무관하다.

# ENTRYPOINT(exec 형식): JVM을 PID 1로 직접 실행해 docker stop의 SIGTERM을 받아 정상 종료(graceful shutdown)할 수 있다.
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
