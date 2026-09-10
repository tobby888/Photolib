-- 「选题查看」拆成两条权限：
--   PROJECT_VIEW      —— 只看得到自己接到需求的选题；
--   PROJECT_VIEW_ALL  —— 无条件查看全部选题。
--
-- 改动之前，「看得到全部选题」并不是一条权限，而是权限组数据范围的副作用：
-- ProjectService 对 data_scope='CAMPUS' 的账号加参与人过滤，GLOBAL 的账号不加。
-- 因此把 PROJECT_VIEW_ALL 补给所有持有任一选题权限的全局范围权限组，升级后的
-- 可见范围与升级前完全一致；校区范围权限组（含内置 CAMPUS_MANAGER）刻意不补，
-- 它们本来就只看得到自己接到需求的选题。
--
-- 目标表同时出现在 SELECT 里，因此把它包一层派生表：MySQL 只有以这种方式
-- （先物化成临时表）才允许「一边写一边读同一张表」，直接写成子查询会踩 1093。
INSERT INTO permission_group_permission(group_id, permission_code)
SELECT eligible.group_id, 'PROJECT_VIEW_ALL'
FROM (
    SELECT DISTINCT g.id AS group_id
    FROM permission_group g
    JOIN permission_group_permission p ON p.group_id = g.id
    WHERE g.data_scope = 'GLOBAL'
      AND p.permission_code IN ('PROJECT_VIEW', 'PROJECT_ADOPT', 'PROJECT_CREATE',
                                'PROJECT_COMPLETE', 'PROJECT_DOWNLOAD', 'PROJECT_SHARE')
) eligible;
