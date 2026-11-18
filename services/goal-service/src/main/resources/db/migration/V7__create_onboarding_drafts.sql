-- 온보딩 임시 저장(ERD 3.1-3). goal-service 소유. users는 user-service가 먼저 만든다(FK 대상).
-- 사용자당 1행이며, 온보딩을 완료하면 같은 트랜잭션에서 지운다.
CREATE TABLE onboarding_drafts (
    id           BIGINT   NOT NULL AUTO_INCREMENT,
    user_id      BIGINT   NOT NULL,
    current_step INT      NOT NULL DEFAULT 1,
    -- 프론트엔드가 정하는 자유 JSON(최대 64KB는 API에서 검사한다). 서버는 내용을 해석하지 않는다.
    draft_data   JSON     NOT NULL,
    version      BIGINT   NOT NULL DEFAULT 0,
    updated_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    -- 사용자당 1행을 DB가 보장한다. 두 인스턴스가 동시에 첫 저장을 해도 행이 중복되지 않는다.
    UNIQUE KEY idx_od_user_id (user_id),
    CONSTRAINT fk_od_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 사용자별 임시 저장 version 카운터. 임시 저장은 삭제(DELETE·온보딩 완료)되면 행이 사라지므로, 새로 만들 때 version을
-- 0부터 다시 시작하면 예전 탭이 들고 있던 version으로 새 임시 저장을 덮어쓸 수 있다. 마지막으로 쓴 version을 여기에
-- 남겨 두고(삭제해도 남는다) 새 임시 저장은 이 값 + 1에서 시작한다. 시계에 의존하지 않아 역행·같은 시각 재생성에도 안전하다.
-- user_settings 엔티티에는 매핑하지 않는다(설정 version을 올리지 않으려고 직접 SQL로만 다룬다).
ALTER TABLE user_settings ADD COLUMN last_onboarding_draft_version BIGINT NOT NULL DEFAULT 0;
