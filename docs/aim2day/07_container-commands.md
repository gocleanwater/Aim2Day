# Aim2Day 컨테이너 빌드·실행·검증 명령어

이 문서는 서버에서 실행하는 모든 `docker build`·`docker run` 명령의 원본이다. 개발 중 재실행, 보고서의 "명령어 전체", 시연 영상은 모두 이 문서의 명령을 위에서부터 순서대로 붙여 넣는다. 명령을 바꿀 때는 이 문서부터 고친다. 스크립트 파일과 Docker Compose는 쓰지 않는다.

- 실행 위치: 리눅스 서버, 저장소 루트(`/opt/aim2day/app`)
- 이름 규칙과 값의 근거: [공유 계약](06_shared-contracts.md) §1·§2
- 비밀번호는 과제 전용 값이다. `!`가 bash에서 특수 문자이므로 작은따옴표로 감싼다.

## 1. 이미지 빌드

직접 만드는 이미지 4개다. MySQL·Redis는 공식 이미지를 그대로 쓴다. `-t`는 이미지 이름, 마지막 인자는 빌드 컨텍스트(Dockerfile이 있는 폴더)다.

```bash
docker build --platform linux/amd64 -t aim2day-user-service services/user-service
docker build --platform linux/amd64 -t aim2day-goal-service services/goal-service
docker build --platform linux/amd64 -t aim2day-frontend     frontend
docker build --platform linux/amd64 -t aim2day-proxy        infra/nginx
```

## 2. 네트워크·볼륨

```bash
docker network create aim2day-net
docker volume create aim2day-mysql-data
```

## 3. 컨테이너 실행 (이 순서대로)

### 3.1 MySQL

```bash
docker run -d --name aim2day-mysql --hostname aim2day-mysql \
  --restart unless-stopped \
  --network aim2day-net \
  -v aim2day-mysql-data:/var/lib/mysql \
  -e MYSQL_ROOT_PASSWORD='rootpass!' \
  -e MYSQL_DATABASE=aim2day \
  -e MYSQL_USER=aim2day_app \
  -e MYSQL_PASSWORD='aim2daypass!' \
  mysql:8.4 \
  --character-set-server=utf8mb4 --collation-server=utf8mb4_unicode_ci
```

- `-v`: DB 파일을 볼륨에 둔다. 컨테이너를 지우고 다시 만들어도 데이터가 남는다.
- `MYSQL_*` 값은 볼륨이 비어 있는 첫 실행 때만 적용된다.
- 이미지 이름(`mysql:8.4`) 뒤의 인자는 MySQL 서버 옵션이다. 한글이 깨지지 않게 utf8mb4로 둔다.
- **다음 단계 전에** 준비될 때까지 기다린다. 아래 명령이 `mysqld is alive`를 출력하면 된다.

```bash
docker exec aim2day-mysql mysqladmin ping -h 127.0.0.1 --silent && echo "mysqld is alive"
```

### 3.2 Redis

```bash
docker run -d --name aim2day-redis --hostname aim2day-redis \
  --restart unless-stopped \
  --network aim2day-net \
  redis:8-alpine
```

세션과 만료되는 토큰만 저장하므로 볼륨을 두지 않는다.

### 3.3 user-service

```bash
docker run -d --name aim2day-user-service --hostname aim2day-user-service \
  --restart unless-stopped \
  --network aim2day-net \
  -e SPRING_DATASOURCE_URL=jdbc:mysql://aim2day-mysql:3306/aim2day \
  -e SPRING_DATASOURCE_USERNAME=aim2day_app \
  -e SPRING_DATASOURCE_PASSWORD='aim2daypass!' \
  -e SPRING_DATA_REDIS_HOST=aim2day-redis \
  aim2day-user-service
```

- `users` 테이블을 이 서비스가 만들고 goal 테이블이 참조하므로 goal보다 먼저 띄운다.
- **다음 단계 전에** 로그에 `Started UserServiceApplication`이 보일 때까지 기다린다.

```bash
docker logs aim2day-user-service 2>&1 | grep "Started UserServiceApplication"
```

### 3.4 goal-service 3개

```bash
docker run -d --name aim2day-goal-service-1 --hostname aim2day-goal-service-1 \
  --restart unless-stopped \
  --network aim2day-net --network-alias goal-services \
  -e SPRING_DATASOURCE_URL=jdbc:mysql://aim2day-mysql:3306/aim2day \
  -e SPRING_DATASOURCE_USERNAME=aim2day_app \
  -e SPRING_DATASOURCE_PASSWORD='aim2daypass!' \
  -e SPRING_DATA_REDIS_HOST=aim2day-redis \
  aim2day-goal-service

docker run -d --name aim2day-goal-service-2 --hostname aim2day-goal-service-2 \
  --restart unless-stopped \
  --network aim2day-net --network-alias goal-services \
  -e SPRING_DATASOURCE_URL=jdbc:mysql://aim2day-mysql:3306/aim2day \
  -e SPRING_DATASOURCE_USERNAME=aim2day_app \
  -e SPRING_DATASOURCE_PASSWORD='aim2daypass!' \
  -e SPRING_DATA_REDIS_HOST=aim2day-redis \
  aim2day-goal-service

docker run -d --name aim2day-goal-service-3 --hostname aim2day-goal-service-3 \
  --restart unless-stopped \
  --network aim2day-net --network-alias goal-services \
  -e SPRING_DATASOURCE_URL=jdbc:mysql://aim2day-mysql:3306/aim2day \
  -e SPRING_DATASOURCE_USERNAME=aim2day_app \
  -e SPRING_DATASOURCE_PASSWORD='aim2daypass!' \
  -e SPRING_DATA_REDIS_HOST=aim2day-redis \
  aim2day-goal-service
```

- `--network-alias goal-services`: 세 컨테이너가 같은 별칭을 가진다. 프록시는 이 이름 하나로 3대에 RR 분산한다.
- `--hostname`: 응답 헤더 `X-Instance-Id` 값이 된다.
- **다음 단계 전에** 세 로그 모두 `Started GoalServiceApplication`이 보일 때까지 기다린다.

### 3.5 frontend

```bash
docker run -d --name aim2day-frontend --hostname aim2day-frontend \
  --restart unless-stopped \
  --network aim2day-net \
  aim2day-frontend
```

### 3.6 proxy (마지막)

```bash
docker run -d --name aim2day-proxy --hostname aim2day-proxy \
  --restart unless-stopped \
  --network aim2day-net \
  -p 80:80 \
  aim2day-proxy
```

- 유일하게 호스트 포트를 연다.
- Nginx는 시작할 때 upstream 이름을 한 번만 찾는다. 대상 컨테이너가 없으면 `host not found`로 종료되므로 마지막에 띄운다. goal 컨테이너를 다시 만들었다면 `docker exec aim2day-proxy nginx -s reload`를 실행한다.

## 4. 확인

```bash
# 컨테이너 8개 동작
docker ps --filter "name=aim2day-" --format "table {{.Names}}\t{{.Status}}\t{{.Ports}}"

# 네트워크에 붙은 컨테이너와 별칭
docker network inspect aim2day-net

# RR 분산: 여러 번 실행하면 X-Instance-Id가 goal-service-1·2·3으로 바뀐다
curl -s -o /dev/null -D - http://localhost/api/goals | grep -i x-instance-id

# 컨테이너 내부 접속
docker exec -it aim2day-user-service bash

# DB 영속성: MySQL 컨테이너를 지우고 3.1로 다시 만든 뒤 데이터가 남아 있는지 확인
docker exec -it aim2day-mysql mysql -uaim2day_app -p'aim2daypass!' aim2day -e "SHOW TABLES;"
```

세션 공유 확인(로그인 후 goal-service 세 인스턴스에서 같은 세션으로 인증)은 로그인 API를 구현한 뒤 이 절에 추가한다.

## 5. 다시 만들기

같은 이름의 컨테이너가 있으면 `docker run`이 실패한다. 먼저 지우고 해당 절의 명령을 다시 실행한다. MySQL 데이터는 볼륨에 있으므로 지워지지 않는다.

```bash
docker rm -f aim2day-goal-service-1 aim2day-goal-service-2 aim2day-goal-service-3
```
