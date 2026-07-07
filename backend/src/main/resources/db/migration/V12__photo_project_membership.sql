-- 支持「一图多项目」：photo 与 project 的多对多归属表。
-- 旧系统 PhotoWarehouse 里照片按标签归属，一张照片可同时出现在多个项目相册中；
-- 新库原本用单值列 photo.project_id 表达归属，无法容纳多归属。此表作为「项目相册展示 /
-- 项目照片计数」的归属来源，photo.project_id 保留为「主/来源项目」不变（adoption、上传
-- 记录、排名仍用它）。约定同 adoption 表：不写 ENGINE/CHARSET，内联 INDEX 与外键。
CREATE TABLE photo_project (
    photo_id BIGINT NOT NULL,
    project_id BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (photo_id, project_id),
    CONSTRAINT fk_pp_photo FOREIGN KEY (photo_id) REFERENCES photo(id),
    CONSTRAINT fk_pp_project FOREIGN KEY (project_id) REFERENCES project(id),
    INDEX idx_pp_project (project_id)
);

-- 回填现有单值归属，保证已有链接不回退。每张照片至多一个 project_id，回填结果对
-- (photo_id, project_id) 天然唯一，不会撞主键，无需 INSERT IGNORE。
INSERT INTO photo_project (photo_id, project_id)
SELECT id, project_id FROM photo
WHERE project_id IS NOT NULL AND deleted = 0;
