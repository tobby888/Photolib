-- Durable failed-login counters. In-process counters would reset on every
-- restart and would not be shared between instances, which is exactly the
-- window a password-guessing run needs; the audit trail alone cannot stop one.
--
-- Two scopes share the table: IDENTIFIER locks a single account under attack,
-- ADDRESS caps how fast one client can spray many accounts. attempt_key holds
-- the normalized login identifier or the client address, both truncated to fit
-- the unique index.
CREATE TABLE login_attempt (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    scope VARCHAR(16) NOT NULL,
    attempt_key VARCHAR(190) NOT NULL,
    failure_count INT NOT NULL DEFAULT 0,
    first_failed_at DATETIME(6) NOT NULL,
    last_failed_at DATETIME(6) NOT NULL,
    locked_until DATETIME(6) NULL,
    CONSTRAINT uk_login_attempt_scope_key UNIQUE (scope, attempt_key),
    INDEX idx_login_attempt_last_failed (last_failed_at)
);
