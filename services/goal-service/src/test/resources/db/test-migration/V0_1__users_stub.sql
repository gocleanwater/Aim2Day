-- 테스트(H2) 전용. 운영에서는 user-service가 users 테이블을 만든다. FK 대상으로만 필요한 최소 형태다.
CREATE TABLE users (
    id BIGINT NOT NULL AUTO_INCREMENT,
    PRIMARY KEY (id)
);
