# 서버 E2E (시험용 DB)

리뷰 전 코드를 서버의 실제 MySQL·Redis로 검증하는 스크립트다. 운영 DB `aim2day`가 아니라 같은 MySQL 안의 시험용 DB `aim2day_review`를 쓴다. 리뷰 뒤 마이그레이션 파일이 바뀌어도 운영 DB의 Flyway 체크섬이 어긋나지 않게 하기 위해서다.

H2 테스트로는 MySQL의 행 잠금·격리 수준·교착을 똑같이 재현할 수 없다. 동시성 규칙(한도, 버전 충돌, 진행률 원자 갱신, 잠금 순서)은 여기서 확인한다.

## 실행 (서버에서)

```bash
# 1) 로컬: goal-service 소스를 묶어 서버 /tmp/gs.tgz로, 이 폴더를 /tmp/e2e로 복사
# 2) 서버
bash /tmp/e2e/review_up.sh reset   # review 컨테이너 3개 기동(user 1, goal 2). reset은 시험용 DB의 goal 테이블을 비움
cd /tmp/e2e && python3 unit2_yearly_criteria.py
bash /tmp/e2e/review_down.sh       # WARN/ERROR 로그 요약 후 정리
```

- `review_up.sh`: user-service는 운영 이미지를 메일 로그 모드로, goal-service는 업로드한 소스로 빌드해 두 대를 띄운다. 프록시를 거치지 않고 컨테이너 IP로 직접 요청한다. 휴지통 정리 시험(단위 6)은 `AIM2DAY_TRASH_PURGE_CRON="*/5 * * * * *" bash /tmp/e2e/review_up.sh`처럼 정리 주기를 짧게 넘긴다.
- `e2elib.py`: 가입·로그인(로그의 인증번호 사용), CSRF·세션 쿠키, 동시 요청(`parallel`), 시험용 DB 조회, 테스트 사용자 정리.
- 단위별 스크립트: `unit2_yearly_criteria.py` …
