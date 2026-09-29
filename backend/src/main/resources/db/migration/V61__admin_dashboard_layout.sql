-- 管理员面板「数据面板」的布局：每位管理员一份，存他自己摆好的小面板（类型、指标、尺寸、顺序）。
--
-- 布局只是界面偏好，不是业务数据：没有软删除列，「恢复默认」直接删掉这一行，前端回到内置的
-- 默认布局。layout_json 由 AdminDashboardLayoutService 按白名单校验后写入，前端读回时再按
-- 指标目录过滤一次，已下线的指标不会让面板崩掉。version 做乐观锁：同一个人在两个标签页里
-- 各改各的，后保存的那一边会被拒绝并提示刷新，而不是悄悄覆盖前一边。
CREATE TABLE admin_dashboard_layout (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    layout_json MEDIUMTEXT NOT NULL,
    version INT NOT NULL DEFAULT 1,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT uk_admin_dashboard_layout_user UNIQUE (user_id),
    CONSTRAINT fk_admin_dashboard_layout_user FOREIGN KEY (user_id) REFERENCES app_user(id)
);
