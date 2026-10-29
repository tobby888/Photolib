-- 分享链接可以只开放选题相册里的一部分图片（建链接时在相册里勾选）。
--
-- 这是一个**可见范围**，不是图片列表的副本：访客看到的仍是 photo_project 现查的相册
-- （限 AVAILABLE），只是再和这张表取交集。图片被移出选题、被删除或还在处理中，
-- 访客照样看不到；被引状态照旧现查 adoption。之后新加进相册的图片不会自动出现在
-- 限定范围的链接里——这正是"只给他看这几张"的意思。
--
-- selected_photos_only 单独一列而不是"表里有没有行"：范围里的图片全被移出相册之后，
-- 链接应当是空的，而不是退回成整个相册。
ALTER TABLE project_share_link ADD COLUMN selected_photos_only BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE project_share_link_photo (
    link_id BIGINT NOT NULL,
    photo_id BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (link_id, photo_id),
    CONSTRAINT fk_share_link_photo_link FOREIGN KEY (link_id) REFERENCES project_share_link(id),
    CONSTRAINT fk_share_link_photo_photo FOREIGN KEY (photo_id) REFERENCES photo(id),
    INDEX idx_share_link_photo_photo (photo_id)
);
