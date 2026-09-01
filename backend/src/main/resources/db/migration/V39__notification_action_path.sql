-- 投递记录自带跳转路径。
--
-- 原来 deliver 按 event_type 现推（REQUEST_* → /requests），管理消息推不出东西来：
-- 它要跳的是这条消息本身（/notifications/{id}），而那个 id 只有写入站内信的那一刻知道。
-- 存下来而不是重算，顺带去掉了投递时对事件类型的二次推导。
ALTER TABLE notification_log ADD COLUMN action_path VARCHAR(255) NULL;
