-- 기간별 직접 회고(ERD 3.6-13). goal-service 소유. users는 user-service가 먼저 만든다(FK 대상).
-- 사용자·기간 유형·기간 식별값마다 1행이다. 회고는 지우지 않으므로 삭제 API도, version 카운터도 없다.
CREATE TABLE period_reviews (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    user_id      BIGINT       NOT NULL,
    period_type  VARCHAR(10)  NOT NULL,
    period_value VARCHAR(20)  NOT NULL,
    improvements TEXT         NULL,
    next_focus   TEXT         NULL,
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version      BIGINT       NOT NULL DEFAULT 0,
    updated_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    -- 두 인스턴스가 같은 기간의 첫 저장을 동시에 해도 행이 중복되지 않는다(users 행 잠금이 1차, 이 제약이 2차 방어선).
    UNIQUE KEY idx_pr_user_period (user_id, period_type, period_value),
    CONSTRAINT fk_pr_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
