#!/usr/bin/env bash
# 이미지 빌드 도커 명령어 (원본: docs/aim2day/07_container-commands.md §1)
# 저장소 루트(/opt/aim2day/app)에서 실행한다.
#
# docker build --platform <플랫폼> -t <이미지 이름>[:<태그>] <빌드 컨텍스트 경로>
#   - 빌드 컨텍스트: Dockerfile과 COPY할 파일이 있는 폴더. Dockerfile이 그 안에 있으면 -f는 생략한다.
#   - 태그를 생략하면 latest가 붙는다.
set -euo pipefail

# user-service
docker build --platform linux/amd64 -t aim2day-user-service services/user-service

# goal-service (컨테이너 3개가 이 이미지 하나를 같이 쓴다)
docker build --platform linux/amd64 -t aim2day-goal-service services/goal-service

# frontend
docker build --platform linux/amd64 -t aim2day-frontend frontend

# proxy
docker build --platform linux/amd64 -t aim2day-proxy infra/nginx
