CREATE TABLE branding_setting (
    id INT PRIMARY KEY,
    icon VARCHAR(32) NOT NULL,
    slogan VARCHAR(80) NOT NULL,
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6)
);

INSERT INTO branding_setting (id, icon, slogan)
VALUES (1, 'camera', '摄影工作站');
