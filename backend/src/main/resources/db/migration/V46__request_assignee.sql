-- 新建需求时可以把需求指派给某个用户。被指派人须持有「需求访问、接受和提交」
-- （REQUEST_VIEW）并能访问需求所在校区；需求发布时被指派人直接成为参与人。
-- 草稿需求要把指派保留到发布那一刻，所以落成一列而不是只写参与人表。
ALTER TABLE photo_request ADD COLUMN assignee_id BIGINT NULL;
ALTER TABLE photo_request ADD CONSTRAINT fk_request_assignee
    FOREIGN KEY (assignee_id) REFERENCES app_user(id);
