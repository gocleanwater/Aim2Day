-- 사용자 환경설정(ERD 3.1-2). goal-service 소유. users는 user-service가 먼저 만든다(FK 대상).
CREATE TABLE user_settings (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    user_id        BIGINT       NOT NULL,
    week_start_day VARCHAR(10)  NOT NULL DEFAULT 'SUNDAY',
    timezone       VARCHAR(50)  NOT NULL DEFAULT 'Asia/Seoul',
    created_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version        BIGINT       NOT NULL DEFAULT 0,
    updated_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    -- 사용자당 1행을 DB가 보장한다. 3개 인스턴스가 동시에 첫 조회를 처리해도 행이 중복되지 않는다.
    UNIQUE KEY idx_us_user_id (user_id),
    CONSTRAINT fk_us_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
