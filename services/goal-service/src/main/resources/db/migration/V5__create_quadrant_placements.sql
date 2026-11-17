-- 일일 사분면 좌표(ERD 3.3-9). goal-service 소유.
-- 행이 없으면 미분류(트레이)다. 확정 좌표(importance_x, urgency_y)와 미확정 좌표(pending_*)를 따로 둬서,
-- 확정한 점을 옮겨도 [오늘 분류 확정] 전까지 이전 확정 좌표가 남는다.
CREATE TABLE action_quadrant_placements (
    id                    BIGINT   NOT NULL AUTO_INCREMENT,
    action_id             BIGINT   NOT NULL,
    classification_date   DATE     NOT NULL,
    importance_x          INT      NULL,
    urgency_y             INT      NULL,
    confirmed_at          DATETIME NULL,
    pending_importance_x  INT      NULL,
    pending_urgency_y     INT      NULL,
    is_pending_unclassify BOOLEAN  NOT NULL DEFAULT FALSE,
    created_at            DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at            DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    -- 같은 행동·같은 날짜에 행이 둘 생기지 않게 DB가 막는다. 쓰기는 users 행 잠금으로 직렬화하므로 정상 경로에서는 위반이 나지 않는 안전망이다.
    UNIQUE KEY idx_aqp_action_date (action_id, classification_date),
    CONSTRAINT fk_aqp_action FOREIGN KEY (action_id) REFERENCES actions (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 마지막으로 [오늘 분류 확정]을 누른 날(계정 시간대 기준). 설정 수정이 아니므로 version을 올리지 않고 직접 UPDATE로만 바꾼다.
ALTER TABLE user_settings ADD COLUMN last_quadrant_confirmed_date DATE NULL;
