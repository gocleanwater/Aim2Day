#!/usr/bin/env bash
# 시험용 DB(aim2day_review)와 시험용 Redis(aim2day-redis-review)에 review 컨테이너를 띄운다: user-service 1(운영 이미지, 로그 메일), goal-service 2(업로드한 소스)
# 사용: review_up.sh [reset]   reset이면 aim2day_review의 goal-service 테이블과 Flyway 이력을 비운다(시험용 DB만)
set -e
MODE=${1:-}
docker rm -f aim2day-user-service-review aim2day-goal-service-review-1 aim2day-goal-service-review-2 aim2day-redis-review >/dev/null 2>&1 || true
# 세션도 운영과 분리한다. 운영 Redis를 같이 쓰면 시험용 DB의 사용자 ID가 운영 사용자 ID와 겹칠 때
# 시험용 세션으로 운영 API에 그 ID로 인증된다(세션에는 userId만 있다).
docker run -d --name aim2day-redis-review --network aim2day-net redis:8-alpine >/dev/null
if [ "$MODE" = reset ]; then
  T=$(docker exec aim2day-mysql mysql -uaim2day_app -p'aim2daypass!' aim2day_review -N -e "SELECT table_name FROM information_schema.tables WHERE table_schema='aim2day_review' AND table_name NOT IN ('users','flyway_user_service_history')" 2>/dev/null | tr '\n' ',' | sed 's/,$//')
  if [ -n "$T" ]; then
    docker exec aim2day-mysql mysql -uaim2day_app -p'aim2daypass!' aim2day_review -e "SET FOREIGN_KEY_CHECKS=0; DROP TABLE IF EXISTS $T; SET FOREIGN_KEY_CHECKS=1;" 2>/dev/null
    echo "reset review tables: $T"
  fi
fi
rm -rf /tmp/gs-review && mkdir -p /tmp/gs-review && tar -xzf /tmp/gs.tgz -C /tmp/gs-review
docker build -q -t aim2day-goal-service:review /tmp/gs-review/goal-service >/dev/null 2>&1
DB="-e SPRING_DATASOURCE_URL=jdbc:mysql://aim2day-mysql:3306/aim2day_review -e SPRING_DATASOURCE_USERNAME=aim2day_app -e SPRING_DATASOURCE_PASSWORD=aim2daypass! -e SPRING_DATA_REDIS_HOST=aim2day-redis-review"
# 업로드한 user-service 소스(/tmp/us.tgz)가 있으면 그것으로 시험용 이미지를 만들고, 없으면 운영 이미지를 쓴다.
USER_IMAGE=aim2day-user-service
if [ -f /tmp/us.tgz ]; then
  rm -rf /tmp/us-review && mkdir -p /tmp/us-review && tar -xzf /tmp/us.tgz -C /tmp/us-review
  docker build -q -t aim2day-user-service:review /tmp/us-review/user-service >/dev/null 2>&1 && USER_IMAGE=aim2day-user-service:review
  echo "user-service image: $USER_IMAGE"
fi
docker run -d --name aim2day-user-service-review --hostname aim2day-user-service-review --network aim2day-net $DB -e APP_BASE_URL=http://192.168.136.128 $USER_IMAGE >/dev/null
for t in $(seq 1 60); do docker logs aim2day-user-service-review 2>&1 | grep -q "Started UserServiceApplication" && break; sleep 2; done
# 휴지통 정리 주기를 짧게 하려면 실행 전에 AIM2DAY_TRASH_PURGE_CRON을 넘긴다(예: "*/5 * * * * *"). 비우면 기본값(매시 정각).
EXTRA=()
if [ -n "${AIM2DAY_TRASH_PURGE_CRON:-}" ]; then EXTRA=(-e "AIM2DAY_TRASH_PURGE_CRON=$AIM2DAY_TRASH_PURGE_CRON"); fi
for i in 1 2; do docker run -d --name aim2day-goal-service-review-$i --hostname aim2day-goal-service-review-$i --network aim2day-net $DB "${EXTRA[@]}" aim2day-goal-service:review >/dev/null; done
for i in 1 2; do
  for t in $(seq 1 60); do docker logs aim2day-goal-service-review-$i 2>&1 | grep -qE "Started GoalServiceApplication|APPLICATION FAILED|Application run failed" && break; sleep 2; done
  echo "goal-review-$i: $(docker logs aim2day-goal-service-review-$i 2>&1 | grep -oE 'Started GoalServiceApplication in [0-9.]+ seconds|APPLICATION FAILED TO START|Application run failed' | head -1)"
done
docker logs aim2day-goal-service-review-1 2>&1 | grep -E "Migrating schema|Successfully applied|up to date" | sed -E 's/^.*(Migrating|Successfully|Schema)/\1/'
