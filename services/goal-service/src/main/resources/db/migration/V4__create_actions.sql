-- 행동(ERD 3.3-8). goal-service 소유. weekly_goal_id가 NULL이면 목표 없는 "그 밖의 할 일"(MAINTENANCE)이다.
CREATE TABLE actions (
    id                 BIGINT         NOT NULL AUTO_INCREMENT,
    user_id            BIGINT         NOT NULL,
    weekly_goal_id     BIGINT         NULL,
    title              VARCHAR(255)   NOT NULL,
    performed_date     DATE           NOT NULL,
    due_date           DATE           NULL,
    is_completed       BOOLEAN        NOT NULL DEFAULT FALSE,
    completed_at       DATETIME       NULL,
    contribution_value DECIMAL(10, 2) NULL,
    result_summary     TEXT           NULL,
    deleted_at         DATETIME       NULL,
    created_at         DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version            BIGINT         NOT NULL DEFAULT 0,
    updated_at         DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_act_user_perf_date (user_id, performed_date, deleted_at),
    KEY idx_act_weekly_goal (weekly_goal_id, is_completed),
    CONSTRAINT fk_act_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT fk_act_weekly FOREIGN KEY (weekly_goal_id) REFERENCES weekly_goals (id) ON DELETE RESTRICT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- V3에서 FK 없이 만든 weekly_goal_numeric_records.action_id에 FK를 더한다(ERD 3.5-12).
-- 행동이 hard-delete되어도 완료 기록은 남도록 SET NULL이다.
ALTER TABLE weekly_goal_numeric_records
    ADD CONSTRAINT fk_wgnr_action FOREIGN KEY (action_id) REFERENCES actions (id) ON DELETE SET NULL;
