-- 달력 항목(ERD 3.4-10). goal-service 소유. 해야 할 일(TASK)·일정(EVENT)이며 hard-delete 정책이다.
-- yearly_goal_id는 ON DELETE SET NULL이다. 30일 휴지통 정리가 연간 목표를 영구 삭제할 때 연결된 달력 항목이
-- FK에 막히지 않고 "목표 없는 항목"으로 남게 한다(달력 항목은 목표 삭제와 무관하게 사용자의 일정이다).
CREATE TABLE calendar_items (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    user_id        BIGINT       NOT NULL,
    yearly_goal_id BIGINT       NULL,
    item_type      VARCHAR(10)  NOT NULL,
    title          VARCHAR(255) NOT NULL,
    description    TEXT         NULL,
    start_date     DATE         NOT NULL,
    end_date       DATE         NOT NULL,
    is_completed   BOOLEAN      NOT NULL DEFAULT FALSE,
    completed_at   DATETIME     NULL,
    created_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version        BIGINT       NOT NULL DEFAULT 0,
    updated_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_ci_user_type_dates (user_id, item_type, start_date, end_date, is_completed),
    KEY idx_ci_goal_filter (yearly_goal_id),
    CONSTRAINT fk_ci_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_ci_yearly FOREIGN KEY (yearly_goal_id) REFERENCES yearly_goals (id) ON DELETE SET NULL
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
