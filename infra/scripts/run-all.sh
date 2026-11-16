#!/usr/bin/env bash
# 도커 컨테이너 실행 명령문 (원본: docs/aim2day/07_container-commands.md §2·§3)
# 명령을 바꿀 때는 07 문서를 먼저 고치고 이 파일을 맞춘다.
set -euo pipefail

# 같은 이름의 컨테이너가 있으면 지운다(처음 실행이면 지울 게 없으므로 || true로 실패를 무시).
remove() { docker rm -f "$1" >/dev/null 2>&1 || true; }

# Spring 로그에 "Started ..."가 나올 때까지 최대 120초 기다린다.
wait_spring() {
  for _ in $(seq 1 60); do
    docker logs "$1" 2>&1 | grep -q "Started .*Application" && { echo "  $1 준비됨"; return 0; }
    sleep 2
  done
  echo "  $1 기동 실패"; docker logs --tail 30 "$1"; exit 1
}

# 네트워크 생성 (이미 있으면 건너뜀)
docker network inspect aim2day-net >/dev/null 2>&1 || docker network create aim2day-net

# mysql
## mysql 도커 볼륨 생성 (이미 있으면 건너뜀. 데이터는 여기에 남는다)
docker volume inspect aim2day-mysql-data >/dev/null 2>&1 || docker volume create aim2day-mysql-data

## mysql 컨테이너 실행
## 이미지 이름(mysql:8.4)은 Docker 옵션(-e, -v ...)을 모두 쓴 뒤 한 번만 쓴다.
## 이미지 이름 뒤의 인자(--character-set-server ...)는 MySQL 서버에 넘어간다.
remove aim2day-mysql
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

# MYSQL 컨테이너가 완전히 시작될 때까지 대기
until docker exec aim2day-mysql mysqladmin ping -h 127.0.0.1 --silent >/dev/null 2>&1; do sleep 2; done
echo "  aim2day-mysql 준비됨"

# redis
remove aim2day-redis
docker run -d --name aim2day-redis --hostname aim2day-redis \
  --restart unless-stopped \
  --network aim2day-net \
  redis:8-alpine

# user-service (users 테이블을 만들므로 goal-service보다 먼저)
remove aim2day-user-service
docker run -d --name aim2day-user-service --hostname aim2day-user-service \
  --restart unless-stopped \
  --network aim2day-net \
  -e SPRING_DATASOURCE_URL=jdbc:mysql://aim2day-mysql:3306/aim2day \
  -e SPRING_DATASOURCE_USERNAME=aim2day_app \
  -e SPRING_DATASOURCE_PASSWORD='aim2daypass!' \
  -e SPRING_DATA_REDIS_HOST=aim2day-redis \
  aim2day-user-service
wait_spring aim2day-user-service

# goal-service 3개 (같은 이미지, 같은 별칭 goal-services, hostname은 X-Instance-Id 값)
for i in 1 2 3; do
  remove "aim2day-goal-service-$i"
  docker run -d --name "aim2day-goal-service-$i" --hostname "aim2day-goal-service-$i" \
    --restart unless-stopped \
    --network aim2day-net --network-alias goal-services \
    -e SPRING_DATASOURCE_URL=jdbc:mysql://aim2day-mysql:3306/aim2day \
    -e SPRING_DATASOURCE_USERNAME=aim2day_app \
    -e SPRING_DATASOURCE_PASSWORD='aim2daypass!' \
    -e SPRING_DATA_REDIS_HOST=aim2day-redis \
    aim2day-goal-service
done
for i in 1 2 3; do wait_spring "aim2day-goal-service-$i"; done

# frontend
remove aim2day-frontend
docker run -d --name aim2day-frontend --hostname aim2day-frontend \
  --restart unless-stopped \
  --network aim2day-net \
  aim2day-frontend

# proxy (마지막: Nginx는 시작할 때 대상 이름을 찾으므로 나머지가 모두 떠 있어야 한다)
remove aim2day-proxy
docker run -d --name aim2day-proxy --hostname aim2day-proxy \
  --restart unless-stopped \
  --network aim2day-net \
  -p 80:80 \
  aim2day-proxy

docker ps --filter "name=aim2day-" --format "table {{.Names}}\t{{.Status}}\t{{.Ports}}"
