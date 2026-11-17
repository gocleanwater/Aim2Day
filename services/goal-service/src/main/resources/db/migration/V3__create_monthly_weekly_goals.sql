-- 월간·주간 목표와 수치 기록(ERD 3.2-6·7, 3.5-12). goal-service 소유.
CREATE TABLE monthly_goals (
    id             BIGINT        NOT NULL AUTO_INCREMENT,
    yearly_goal_id BIGINT        NOT NULL,
    start_date     DATE          NOT NULL,
    end_date       DATE          NOT NULL,
    title          VARCHAR(100)  NOT NULL,
    description    TEXT          NULL,
    color          VARCHAR(20)   NULL,
    status         VARCHAR(20)   NOT NULL DEFAULT 'PROGRESS',
    progress_rate  DECIMAL(5, 2) NOT NULL DEFAULT 0.00,
    deleted_at     DATETIME      NULL,
    created_at     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version        BIGINT        NOT NULL DEFAULT 0,
    updated_at     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_mg_yearly_goal (yearly_goal_id, start_date, deleted_at),
    CONSTRAINT fk_mg_yearly FOREIGN KEY (yearly_goal_id) REFERENCES yearly_goals (id) ON DELETE RESTRICT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE weekly_goals (
    id               BIGINT        NOT NULL AUTO_INCREMENT,
    monthly_goal_id  BIGINT        NOT NULL,
    start_date       DATE          NOT NULL,
    end_date         DATE          NOT NULL,
    title            VARCHAR(100)  NOT NULL,
    description      TEXT          NULL,
    color            VARCHAR(20)   NULL,
    measurement_type VARCHAR(20)   NOT NULL DEFAULT 'CHECKLIST',
    target_value     DECIMAL(10, 2) NULL,
    current_value    DECIMAL(10, 2) NOT NULL DEFAULT 0.00,
    weight           DECIMAL(5, 2) NOT NULL DEFAULT 1.00,
    status           VARCHAR(20)   NOT NULL DEFAULT 'PROGRESS',
    progress_rate    DECIMAL(5, 2) NOT NULL DEFAULT 0.00,
    deleted_at       DATETIME      NULL,
    created_at       DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version          BIGINT        NOT NULL DEFAULT 0,
    updated_at       DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_wg_monthly_goal (monthly_goal_id, start_date, deleted_at),
    CONSTRAINT fk_wg_monthly FOREIGN KEY (monthly_goal_id) REFERENCES monthly_goals (id) ON DELETE RESTRICT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- action_id는 아직 FK가 없다. actions 테이블을 만드는 단위 4의 마이그레이션이 FK(ON DELETE SET NULL)를 더한다.
-- UNIQUE는 같은 행동 완료가 여러 인스턴스에 동시에 와도 기여 기록이 한 행만 들어가게 한다(NULL은 여러 개 허용).
CREATE TABLE weekly_goal_numeric_records (
    id             BIGINT         NOT NULL AUTO_INCREMENT,
    weekly_goal_id BIGINT         NOT NULL,
    action_id      BIGINT         NULL,
    record_source  VARCHAR(20)    NOT NULL,
    amount         DECIMAL(10, 2) NOT NULL,
    memo           VARCHAR(255)   NULL,
    created_at     DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_wgnr_action_id (action_id),
    KEY idx_wgnr_goal_source (weekly_goal_id, record_source, action_id),
    CONSTRAINT fk_wgnr_weekly FOREIGN KEY (weekly_goal_id) REFERENCES weekly_goals (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
