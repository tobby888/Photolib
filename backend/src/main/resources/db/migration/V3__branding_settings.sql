CREATE TABLE branding_setting (
    id INT PRIMARY KEY,
    title VARCHAR(40) NOT NULL,
    icon_type VARCHAR(16) NOT NULL,
    builtin_icon VARCHAR(32) NOT NULL,
    custom_icon LONGBLOB NULL,
    custom_icon_content_type VARCHAR(64) NULL,
    slogan VARCHAR(80) NOT NULL,
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6)
);

INSERT INTO branding_setting (id, title, icon_type, builtin_icon, slogan)
VALUES (1, 'PhotoLib', 'builtin', 'camera', '摄影工作站');
