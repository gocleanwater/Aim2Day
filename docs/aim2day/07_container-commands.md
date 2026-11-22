# Aim2Day 컨테이너 빌드·실행·검증 명령어

이 문서는 서버에서 실행하는 모든 `docker build`·`docker run` 명령의 원본이다. 보고서의 "명령어 전체"와 시연 영상은 이 문서의 명령을 위에서부터 순서대로 붙여 넣는다. 명령을 바꿀 때는 이 문서부터 고친다. 스크립트 파일과 Docker Compose는 쓰지 않는다.

- 실행 위치: 리눅스 서버(amd64). §1은 저장소 루트(`/opt/aim2day/app`)에서, §2부터는 아무 위치에서나 실행한다.
- 이름 규칙과 값의 근거: [공유 계약](06_shared-contracts.md) §1·§2
- 비밀번호는 과제 전용 값이다. `!`가 bash에서 특수 문자이므로 작은따옴표로 감싼다.
- 실행에는 Docker Hub에 올린 이미지(`jungsooko/aim2day-*:1.0`)를 쓴다. 저장소 소스 없이도 §2부터 실행하면 같은 시스템이 뜬다.

## 1. 이미지 만들기와 Docker Hub 업로드 (개발자가 한 번)

직접 만드는 이미지는 4개다. 각 Dockerfile이 프로그램을 `COPY`로 이미지 안에 넣으므로, 바인딩 마운트 없이 이미지만으로 실행된다. MySQL·Redis는 공식 이미지를 그대로 쓰므로 만들거나 올리지 않는다.

```bash
# 빌드: --platform은 Mac(ARM)에서 빌드해도 amd64 이미지가 나오게 한다. 마지막 인자는 Dockerfile이 있는 폴더다.
docker build --platform linux/amd64 -t aim2day-user-service services/user-service
docker build --platform linux/amd64 -t aim2day-goal-service services/goal-service
docker build --platform linux/amd64 -t aim2day-frontend     frontend
docker build --platform linux/amd64 -t aim2day-proxy        infra/nginx

# Docker Hub 이름표 붙이기(아이디/저장소:태그). 이미지를 복사하지 않고 이름만 하나 더 붙인다.
docker tag aim2day-user-service jungsooko/aim2day-user-service:1.0
docker tag aim2day-goal-service jungsooko/aim2day-goal-service:1.0
docker tag aim2day-frontend     jungsooko/aim2day-frontend:1.0
docker tag aim2day-proxy        jungsooko/aim2day-proxy:1.0

# 업로드(docker login을 한 번 해 둔 상태에서)
docker push jungsooko/aim2day-user-service:1.0
docker push jungsooko/aim2day-goal-service:1.0
docker push jungsooko/aim2day-frontend:1.0
docker push jungsooko/aim2day-proxy:1.0
```

`latest` 태그도 같은 방식으로 함께 올려 두었다.

## 2. 이미지 받기

```bash
docker pull jungsooko/aim2day-user-service:1.0
docker pull jungsooko/aim2day-goal-service:1.0
docker pull jungsooko/aim2day-frontend:1.0
docker pull jungsooko/aim2day-proxy:1.0
docker pull mysql:8.4
docker pull redis:8-alpine
```

`docker run`은 이미지가 없으면 알아서 받으므로 이 절은 생략해도 된다. 미리 받아 두면 §4의 실행이 빨라진다.

## 3. 네트워크·볼륨

```bash
docker network create aim2day-net
docker volume create aim2day-mysql-data
```

- `aim2day-net`: 사용자 정의 브리지 네트워크. 이 네트워크의 컨테이너끼리는 컨테이너 이름으로 서로를 찾는다(Docker 내부 DNS).
- `aim2day-mysql-data`: MySQL 데이터 파일을 둘 볼륨. 컨테이너를 지워도 남는다.

## 4. 컨테이너 실행 (이 순서대로)

### 4.1 MySQL

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

### 4.2 Redis

```bash
docker run -d --name aim2day-redis --hostname aim2day-redis \
  --restart unless-stopped \
  --network aim2day-net \
  redis:8-alpine
```

세션과 만료되는 토큰만 저장하므로 볼륨을 두지 않는다.

### 4.3 user-service

```bash
docker run -d --name aim2day-user-service --hostname aim2day-user-service \
  --restart unless-stopped \
  --network aim2day-net \
  -e SPRING_DATASOURCE_URL=jdbc:mysql://aim2day-mysql:3306/aim2day \
  -e SPRING_DATASOURCE_USERNAME=aim2day_app \
  -e SPRING_DATASOURCE_PASSWORD='aim2daypass!' \
  -e SPRING_DATA_REDIS_HOST=aim2day-redis \
  -e APP_BASE_URL=http://aim2day.com \
  --env-file /opt/aim2day/env/mail.env \
  jungsooko/aim2day-user-service:1.0
```

- `users` 테이블을 이 서비스가 만들고 goal 테이블이 참조하므로 goal보다 먼저 띄운다.
- `--env-file /opt/aim2day/env/mail.env`: Gmail SMTP 자격 증명. 저장소에 없는 파일이다(공유 계약 §2). **이 줄을 빼고 실행하면** 메일 대신 로그에 인증번호 또는 재설정 링크가 찍힌다(받는 주소는 마스킹). SMTP 키 없이 재현할 때는 이 줄을 지우고 `docker logs aim2day-user-service | grep MAIL`로 확인한다.
- `APP_BASE_URL`: 메일 링크의 주소 앞부분. 브라우저로 접속하는 주소와 같아야 한다. 접속 PC의 hosts 파일에 `<서버 IP> aim2day.com`을 등록해 이 이름으로 접속한다(README의 "접속 준비").
- **다음 단계 전에** 로그에 `Started UserServiceApplication`이 보일 때까지 기다린다.

```bash
docker logs aim2day-user-service 2>&1 | grep "Started UserServiceApplication"
```

### 4.4 goal-service 컨테이너 3개

```bash
docker run -d --name aim2day-goal-service-1 --hostname aim2day-goal-service-1 \
  --restart unless-stopped \
  --network aim2day-net --network-alias goal-services \
  -e SPRING_DATASOURCE_URL=jdbc:mysql://aim2day-mysql:3306/aim2day \
  -e SPRING_DATASOURCE_USERNAME=aim2day_app \
  -e SPRING_DATASOURCE_PASSWORD='aim2daypass!' \
  -e SPRING_DATA_REDIS_HOST=aim2day-redis \
  jungsooko/aim2day-goal-service:1.0

docker run -d --name aim2day-goal-service-2 --hostname aim2day-goal-service-2 \
  --restart unless-stopped \
  --network aim2day-net --network-alias goal-services \
  -e SPRING_DATASOURCE_URL=jdbc:mysql://aim2day-mysql:3306/aim2day \
  -e SPRING_DATASOURCE_USERNAME=aim2day_app \
  -e SPRING_DATASOURCE_PASSWORD='aim2daypass!' \
  -e SPRING_DATA_REDIS_HOST=aim2day-redis \
  jungsooko/aim2day-goal-service:1.0

docker run -d --name aim2day-goal-service-3 --hostname aim2day-goal-service-3 \
  --restart unless-stopped \
  --network aim2day-net --network-alias goal-services \
  -e SPRING_DATASOURCE_URL=jdbc:mysql://aim2day-mysql:3306/aim2day \
  -e SPRING_DATASOURCE_USERNAME=aim2day_app \
  -e SPRING_DATASOURCE_PASSWORD='aim2daypass!' \
  -e SPRING_DATA_REDIS_HOST=aim2day-redis \
  jungsooko/aim2day-goal-service:1.0
```

- `--network-alias goal-services`: 세 컨테이너가 같은 별칭을 가진다. 프록시는 이 이름 하나로 3대에 RR 분산한다.
- `--hostname`: 응답 헤더 `X-Instance-Id` 값이 된다.
- **다음 단계 전에** 세 로그 모두 `Started GoalServiceApplication`이 보일 때까지 기다린다.

```bash
for i in 1 2 3; do docker logs aim2day-goal-service-$i 2>&1 | grep "Started GoalServiceApplication"; done
```

### 4.5 frontend

```bash
docker run -d --name aim2day-frontend --hostname aim2day-frontend \
  --restart unless-stopped \
  --network aim2day-net \
  jungsooko/aim2day-frontend:1.0
```

### 4.6 proxy (마지막)

```bash
docker run -d --name aim2day-proxy --hostname aim2day-proxy \
  --restart unless-stopped \
  --network aim2day-net \
  -p 80:80 \
  jungsooko/aim2day-proxy:1.0
```

- 유일하게 호스트 포트를 연다(호스트 80 → 컨테이너 80).
- Nginx는 시작할 때 upstream 이름을 한 번만 찾는다. 대상 컨테이너가 없으면 `host not found`로 종료되므로 마지막에 띄운다. 다른 컨테이너를 다시 만들었다면 `docker restart aim2day-proxy`를 실행한다.

## 5. 확인

### 5.1 컨테이너·네트워크·내부 접속

```bash
# 컨테이너 8개 동작
docker ps --filter "name=aim2day-" --format "table {{.Names}}\t{{.Image}}\t{{.Status}}\t{{.Ports}}"

# 네트워크에 붙은 컨테이너, IP, 별칭
docker network inspect aim2day-net

# 컨테이너 내부 접속(나올 때는 exit)
docker exec -it aim2day-user-service bash
```

### 5.2 라운드 로빈

```bash
# 여러 번 실행하면 X-Instance-Id가 goal-service-1·2·3으로 바뀐다(로그인 전이라 401이어도 헤더는 붙는다)
for i in 1 2 3 4 5 6; do
  curl -s -o /dev/null -D - http://localhost/api/goals/yearly | grep -i x-instance-id
done
```

### 5.3 서버 간 세션 공유

user-service에서 로그인해 받은 `SESSION` 쿠키 하나로, goal-service 세 인스턴스가 모두 인증을 통과하는지 본다. 가입된 계정이 하나 있어야 한다(브라우저에서 가입).

```bash
# 0) 계정 입력(비밀번호는 화면에 표시되지 않는다)
read -p "email: " EMAIL; read -s -p "password: " PW; echo

# 1) CSRF 토큰 쿠키를 받는다. 로그인(POST)은 쿠키 값을 X-XSRF-TOKEN 헤더로 함께 보내야 한다.
curl -s -c jar.txt -o /dev/null http://localhost/api/auth/csrf
XSRF=$(awk '$6=="XSRF-TOKEN"{print $7}' jar.txt)

# 2) 로그인: user-service가 Redis에 세션을 저장하고 SESSION 쿠키를 준다.
curl -s -b jar.txt -c jar.txt -H "Content-Type: application/json" -H "X-XSRF-TOKEN: $XSRF" \
  -d "{\"email\":\"$EMAIL\",\"password\":\"$PW\"}" http://localhost/api/auth/login; echo

# 3) Redis에 저장된 세션
docker exec aim2day-redis redis-cli --scan --pattern "spring:session:sessions:*"

# 4) 같은 SESSION 쿠키로 goal-service API를 6번 요청: 처리 인스턴스가 바뀌어도 모두 200
for i in 1 2 3 4 5 6; do
  curl -s -b jar.txt -o /dev/null -D - http://localhost/api/goals/yearly | grep -iE "^HTTP|x-instance-id" | tr -d '\r' | paste -sd ' '
done

# 5) 비교: 쿠키 없이 요청하면 401
curl -s -o /dev/null -w "without cookie: %{http_code}\n" http://localhost/api/goals/yearly
```

### 5.4 DB 볼륨 마운트·데이터 영속성

MySQL 컨테이너를 재시작한 뒤 데이터가 그대로인지 본다(시연 영상은 이 방법을 쓴다).

```bash
# 1) 볼륨 마운트 확인
docker inspect aim2day-mysql --format '{{range .Mounts}}{{.Type}}: {{.Name}} -> {{.Destination}}{{println}}{{end}}'

# 2) 재시작 전 데이터(--default-character-set: 한글 제목이 ???로 깨지지 않게 클라이언트 문자셋을 맞춘다)
docker exec aim2day-mysql mysql -uaim2day_app -p'aim2daypass!' --default-character-set=utf8mb4 aim2day \
  -e "SELECT id, title, created_at FROM yearly_goals WHERE deleted_at IS NULL;"

# 3) 재시작: STATUS가 "Up 몇 초"로 바뀐다
docker restart aim2day-mysql
docker ps --filter "name=aim2day-mysql"

# 4) 준비를 기다린다
docker exec aim2day-mysql mysqladmin ping -h 127.0.0.1 --silent && echo "mysqld is alive"

# 5) 같은 데이터가 남아 있다
docker exec aim2day-mysql mysql -uaim2day_app -p'aim2daypass!' --default-character-set=utf8mb4 aim2day \
  -e "SELECT id, title, created_at FROM yearly_goals WHERE deleted_at IS NULL;"
```

더 강한 확인: 컨테이너를 완전히 지우고 §4.1 명령으로 새로 만들어도 데이터가 남는지 본다. 재시작은 같은 컨테이너를 다시 켜는 것이라 볼륨이 없어도 데이터가 남지만, 삭제 후 재생성은 볼륨이 있어야만 데이터가 남는다. 보고서 9.6 캡처는 이 방법으로 찍었다.

```bash
# 1) 볼륨 마운트 확인
docker inspect aim2day-mysql --format '{{range .Mounts}}{{.Type}}: {{.Name}} -> {{.Destination}}{{println}}{{end}}'

# 2) 지우기 전 데이터(--default-character-set: 한글 제목이 ???로 깨지지 않게 클라이언트 문자셋을 맞춘다)
docker exec aim2day-mysql mysql -uaim2day_app -p'aim2daypass!' --default-character-set=utf8mb4 aim2day \
  -e "SELECT id, title, created_at FROM yearly_goals WHERE deleted_at IS NULL;"

# 3) 컨테이너 삭제(볼륨은 남는다)
docker rm -f aim2day-mysql
docker ps -a --filter "name=aim2day-mysql"
docker volume ls --filter "name=aim2day-mysql-data"

# 4) §4.1의 docker run 명령으로 다시 만들고 준비를 기다린다
#    (명령 생략: §4.1과 같다)
docker exec aim2day-mysql mysqladmin ping -h 127.0.0.1 --silent && echo "mysqld is alive"

# 5) 같은 데이터가 남아 있다
docker exec aim2day-mysql mysql -uaim2day_app -p'aim2daypass!' --default-character-set=utf8mb4 aim2day \
  -e "SELECT id, title, created_at FROM yearly_goals WHERE deleted_at IS NULL;"
```

서비스는 DB 연결이 끊긴 뒤 다시 연결한다. 연결 풀이 새 연결을 만들 때 `aim2day-mysql` 이름을 다시 찾으므로, 새 컨테이너의 IP가 바뀌어도 서비스를 다시 띄울 필요가 없다.

## 6. 다시 만들기

같은 이름의 컨테이너가 있으면 `docker run`이 실패한다. 먼저 지우고 해당 절의 명령을 다시 실행한다. MySQL 데이터는 볼륨에 있으므로 지워지지 않는다.

```bash
# 전체 삭제(볼륨은 그대로)
docker rm -f aim2day-proxy aim2day-frontend aim2day-goal-service-1 aim2day-goal-service-2 \
  aim2day-goal-service-3 aim2day-user-service aim2day-redis aim2day-mysql
docker network rm aim2day-net
```
