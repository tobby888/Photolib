CREATE TABLE permission_group (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    code VARCHAR(64) NOT NULL,
    name VARCHAR(100) NOT NULL,
    description VARCHAR(500) NULL,
    data_scope VARCHAR(16) NOT NULL,
    built_in BOOLEAN NOT NULL DEFAULT FALSE,
    lowest BOOLEAN NOT NULL DEFAULT FALSE,
    version INT NOT NULL DEFAULT 1,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT uk_permission_group_code UNIQUE (code),
    INDEX idx_permission_group_lowest (lowest, deleted)
);

CREATE TABLE permission_group_permission (
    group_id BIGINT NOT NULL,
    permission_code VARCHAR(64) NOT NULL,
    PRIMARY KEY (group_id, permission_code),
    CONSTRAINT fk_group_permission_group FOREIGN KEY (group_id) REFERENCES permission_group(id)
        ON DELETE CASCADE
);

INSERT INTO permission_group(code, name, description, data_scope, built_in, lowest) VALUES
    ('ADMIN', '系统管理员', '系统内置管理员权限组', 'GLOBAL', TRUE, FALSE),
    ('MINISTER', '摄影部部长', '系统内置部长权限组', 'GLOBAL', TRUE, FALSE),
    ('CAMPUS_MANAGER', '校区负责人', '系统内置校区负责人权限组', 'CAMPUS', TRUE, FALSE),
    ('NO_ACCESS', '待分配权限', '权限组被删除或尚未完成授权的最低权限组', 'NONE', TRUE, TRUE);

INSERT INTO permission_group_permission(group_id, permission_code)
SELECT id, permission_code
FROM permission_group
CROSS JOIN (
    SELECT 'PROJECT_VIEW' permission_code UNION ALL
    SELECT 'PROJECT_ADOPT' UNION ALL SELECT 'PROJECT_CREATE' UNION ALL
    SELECT 'PROJECT_COMPLETE' UNION ALL SELECT 'PROJECT_DOWNLOAD' UNION ALL
    SELECT 'PHOTO_VIEW' UNION ALL SELECT 'PHOTO_DELETE' UNION ALL
    SELECT 'PHOTO_UPLOAD' UNION ALL SELECT 'PHOTO_DOWNLOAD' UNION ALL
    SELECT 'REQUEST_VIEW' UNION ALL SELECT 'REQUEST_CREATE' UNION ALL
    SELECT 'REQUEST_DELETE' UNION ALL SELECT 'REQUEST_CLOSE' UNION ALL
    SELECT 'REQUEST_CONFIRM' UNION ALL SELECT 'REQUEST_PHOTO_MANAGE' UNION ALL
    SELECT 'WORKLOG_SUBMIT' UNION ALL SELECT 'WORKLOG_CONFIRM' UNION ALL
    SELECT 'WORKLOG_EXPORT' UNION ALL SELECT 'DIRECTORY_VIEW' UNION ALL
    SELECT 'DIRECTORY_MANAGE' UNION ALL SELECT 'MESSAGE_SEND' UNION ALL
    SELECT 'STATISTICS_DOWNLOAD' UNION ALL SELECT 'MANAGER_CAMPUS_ASSIGN'
) permissions
WHERE code = 'ADMIN';

INSERT INTO permission_group_permission(group_id, permission_code)
SELECT id, permission_code
FROM permission_group
CROSS JOIN (
    SELECT 'PROJECT_VIEW' permission_code UNION ALL
    SELECT 'PROJECT_ADOPT' UNION ALL SELECT 'PROJECT_CREATE' UNION ALL
    SELECT 'PROJECT_COMPLETE' UNION ALL SELECT 'PROJECT_DOWNLOAD' UNION ALL
    SELECT 'PHOTO_VIEW' UNION ALL SELECT 'PHOTO_DELETE' UNION ALL
    SELECT 'PHOTO_UPLOAD' UNION ALL SELECT 'PHOTO_DOWNLOAD' UNION ALL
    SELECT 'REQUEST_VIEW' UNION ALL SELECT 'REQUEST_CREATE' UNION ALL
    SELECT 'REQUEST_CLOSE' UNION ALL SELECT 'REQUEST_CONFIRM' UNION ALL
    SELECT 'REQUEST_PHOTO_MANAGE' UNION ALL SELECT 'WORKLOG_CONFIRM' UNION ALL
    SELECT 'WORKLOG_EXPORT' UNION ALL SELECT 'DIRECTORY_VIEW' UNION ALL
    SELECT 'DIRECTORY_MANAGE' UNION ALL
    SELECT 'MESSAGE_SEND' UNION ALL SELECT 'STATISTICS_DOWNLOAD' UNION ALL
    SELECT 'MANAGER_CAMPUS_ASSIGN'
) permissions
WHERE code = 'MINISTER';

INSERT INTO permission_group_permission(group_id, permission_code)
SELECT id, permission_code
FROM permission_group
CROSS JOIN (
    SELECT 'PROJECT_VIEW' permission_code UNION ALL
    SELECT 'PROJECT_ADOPT' UNION ALL
    SELECT 'PHOTO_VIEW' UNION ALL SELECT 'PHOTO_UPLOAD' UNION ALL
    SELECT 'PHOTO_DOWNLOAD' UNION ALL SELECT 'REQUEST_VIEW' UNION ALL
    SELECT 'REQUEST_PHOTO_MANAGE' UNION ALL SELECT 'WORKLOG_SUBMIT' UNION ALL
    SELECT 'DIRECTORY_VIEW' UNION ALL SELECT 'DIRECTORY_MANAGE'
) permissions
WHERE code = 'CAMPUS_MANAGER';

ALTER TABLE app_user ADD COLUMN permission_group_id BIGINT NULL AFTER role;
UPDATE app_user
SET permission_group_id = (SELECT id FROM permission_group WHERE code = app_user.role);
UPDATE app_user
SET permission_group_id = (SELECT id FROM permission_group WHERE code = 'NO_ACCESS')
WHERE permission_group_id IS NULL;
ALTER TABLE app_user ADD CONSTRAINT fk_user_permission_group
    FOREIGN KEY (permission_group_id) REFERENCES permission_group(id);
CREATE INDEX idx_user_permission_group ON app_user(permission_group_id, enabled, deleted);

CREATE TABLE user_campus_permission (
    user_id BIGINT NOT NULL,
    campus_id BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (user_id, campus_id),
    CONSTRAINT fk_user_campus_permission_user FOREIGN KEY (user_id) REFERENCES app_user(id),
    CONSTRAINT fk_user_campus_permission_campus FOREIGN KEY (campus_id) REFERENCES campus(id),
    INDEX idx_user_campus_permission_campus (campus_id, user_id)
);

INSERT INTO user_campus_permission(user_id, campus_id)
SELECT id, campus_id FROM app_user WHERE campus_id IS NOT NULL AND deleted = FALSE;
