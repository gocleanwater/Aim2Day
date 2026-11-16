-- user-service가 소유한 users 테이블(ERD 3.1). goal-service 테이블이 users(id)를 FK로 참조한다.
-- email은 서버가 앞뒤 공백 제거·소문자 변환 후 저장한다. 열 정렬 규칙을 utf8mb4_bin(대소문자·악센트 구분)으로 두어
-- "정규화 결과가 정확히 같을 때만 같은 이메일"로 비교한다. 기본 규칙(대소문자 무시)이면 정규화를 빠뜨린 경로에서
-- 서로 다른 이메일이 UNIQUE 제약상 같은 값으로 취급될 수 있다.
-- 인증번호로 이메일을 먼저 인증한 뒤에만 계정이 만들어지므로 미인증 상태가 없어 is_verified 열은 두지 않는다(ERD 결정 10).
CREATE TABLE users (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    email         VARCHAR(255) COLLATE utf8mb4_bin NOT NULL,
    username      VARCHAR(50)  NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT idx_users_email UNIQUE (email)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
