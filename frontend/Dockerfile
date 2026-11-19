FROM nginx:alpine

# 정적 파일(index.html, css/, js/ ...)을 폴더째 nginx 기본 서빙 폴더로 복사한다.
# 빌드 단계가 없으므로 파일 하나를 고쳐도 이 COPY 한 줄이 그대로 동작한다.
# Dockerfile과 .dockerignore는 .dockerignore에서 제외해 이미지에 들어가지 않는다.
COPY . /usr/share/nginx/html/
