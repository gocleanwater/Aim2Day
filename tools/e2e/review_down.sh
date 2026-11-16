#!/usr/bin/env bash
# review 컨테이너·이미지·임시 파일 정리. 시험용 DB의 테스트 사용자도 지운다(운영 DB는 건드리지 않는다).
for i in 1 2; do echo "goal-review-$i WARN/ERROR:"; docker logs aim2day-goal-service-review-$i 2>&1 | grep -E " (WARN|ERROR) " | grep -v SpringDoc | sed -E "s/^.*(WARN|ERROR)/\1/" | cut -c1-200 | sort | uniq -c | head -20; done
docker rm -f aim2day-user-service-review aim2day-goal-service-review-1 aim2day-goal-service-review-2 aim2day-redis-review >/dev/null 2>&1
docker rmi aim2day-goal-service:review >/dev/null 2>&1
rm -rf /tmp/gs-review /tmp/gs.tgz
echo "aim2day containers running: $(docker ps --format '{{.Names}}' | grep -c '^aim2day-')"
