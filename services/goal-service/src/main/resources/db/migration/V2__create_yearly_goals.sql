-- 연간 목표·달성 기준·진행 기록(ERD 3.2-4·5, 3.5-11). goal-service 소유. users는 user-service가 먼저 만든다(FK 대상).
CREATE TABLE yearly_goals (
    id                       BIGINT       NOT NULL AUTO_INCREMENT,
    user_id                  BIGINT       NOT NULL,
    target_year              INT          NOT NULL,
    title                    VARCHAR(100) NOT NULL,
    description              TEXT         NULL,
    color                    VARCHAR(20)  NOT NULL DEFAULT '#3B82F6',
    display_order            INT          NOT NULL DEFAULT 0,
    status                   VARCHAR(20)  NOT NULL DEFAULT 'PROGRESS',
    is_criteria_confirmed    BOOLEAN      NOT NULL DEFAULT FALSE,
    initial_baseline_rate    INT          NULL,
    criteria_confirmed_at    DATETIME     NULL,
    criteria_last_revised_at DATETIME     NULL,
    deleted_at               DATETIME     NULL,
    created_at               DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version                  BIGINT       NOT NULL DEFAULT 0,
    updated_at               DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_yg_user_year_active (user_id, target_year, deleted_at),
    CONSTRAINT fk_yg_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE RESTRICT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE yearly_goal_criteria (
    id                   BIGINT       NOT NULL AUTO_INCREMENT,
    yearly_goal_id       BIGINT       NOT NULL,
    criterion_text       VARCHAR(255) NOT NULL,
    weight               INT          NOT NULL,
    is_achieved          BOOLEAN      NOT NULL DEFAULT FALSE,
    achieved_at          DATETIME     NULL,
    is_baseline_achieved BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at           DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at           DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_ygc_goal_id (yearly_goal_id),
    CONSTRAINT fk_ygc_goal FOREIGN KEY (yearly_goal_id) REFERENCES yearly_goals (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- target_id는 연간·월간·주간 목표 PK를 가리키는 폴리모픽 참조라 FK를 두지 않는다(목표가 삭제돼도 이력 보존).
CREATE TABLE goal_progress_logs (
    id            BIGINT        NOT NULL AUTO_INCREMENT,
    user_id       BIGINT        NOT NULL,
    target_type   VARCHAR(20)   NOT NULL,
    target_id     BIGINT        NOT NULL,
    previous_rate DECIMAL(5, 2) NOT NULL,
    new_rate      DECIMAL(5, 2) NOT NULL,
    change_type   VARCHAR(30)   NOT NULL DEFAULT 'PROGRESS_UPDATE',
    log_date      DATE          NOT NULL,
    memo          VARCHAR(255)  NULL,
    created_at    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_gpl_user_recent (user_id, created_at DESC),
    KEY idx_gpl_target_date (target_type, target_id, log_date, created_at),
    CONSTRAINT fk_gpl_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
