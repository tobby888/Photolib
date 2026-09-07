-- 站点定制：登录页文案、全站页脚，以及缺图时轮换展示的占位图。
-- 文案列都给 NOT NULL DEFAULT，读到空串就表示"这一项没配"，前端不必区分 null 和 ''。
ALTER TABLE branding_setting ADD COLUMN login_headline VARCHAR(120) NOT NULL DEFAULT '';
ALTER TABLE branding_setting ADD COLUMN login_subheadline VARCHAR(200) NOT NULL DEFAULT '';
ALTER TABLE branding_setting ADD COLUMN login_highlights VARCHAR(400) NOT NULL DEFAULT '[]';
ALTER TABLE branding_setting ADD COLUMN login_notice VARCHAR(200) NOT NULL DEFAULT '';
ALTER TABLE branding_setting ADD COLUMN footer_text VARCHAR(300) NOT NULL DEFAULT '';
ALTER TABLE branding_setting ADD COLUMN footer_links VARCHAR(2000) NOT NULL DEFAULT '[]';

-- 迁移前登录页这几句是写死在 LoginPage.tsx 里的；搬进配置时把原文案作为初值写入，
-- 让升级上来的部署看起来完全没变，之后管理员想改再改。
UPDATE branding_setting
SET login_headline = '让每一次快门，
都抵达它该去的地方。',
    login_subheadline = '从拍摄需求到图片采纳，把散落的协作收进一条清晰的工作流。',
    login_highlights = '["项目协作","素材管理","贡献统计"]',
    login_notice = '首次登录后，系统会引导你修改初始密码'
WHERE id = 1;

CREATE TABLE branding_placeholder_image (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    file_name VARCHAR(200) NOT NULL,
    image LONGBLOB NOT NULL,
    image_content_type VARCHAR(64) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6)
);
