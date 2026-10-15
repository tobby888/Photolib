-- 选题置顶：每个账号各置顶各的，互不影响。
--
-- 置顶是「我常去这几个」的个人书签，不是负责人给所有人的公告，所以按 (user_id, project_id)
-- 存，而不是在 project 上加一列——一列的话一个人置顶就改了所有人的列表。
-- 与 project_selector 同理，这张表只表达集合成员关系：没有 version / deleted，取消置顶直接删行。
CREATE TABLE project_pin (
    user_id BIGINT NOT NULL,
    project_id BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (user_id, project_id),
    CONSTRAINT fk_project_pin_user FOREIGN KEY (user_id) REFERENCES app_user(id),
    CONSTRAINT fk_project_pin_project FOREIGN KEY (project_id) REFERENCES project(id),
    INDEX idx_project_pin_project (project_id)
);
