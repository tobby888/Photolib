-- V18 清过一次软删除账号占用的邮箱，但 UserEntity.email 当时用的是默认的
-- NOT_NULL 更新策略，UserService.delete() 里的 setEmail(null) 从来没写进库，
-- 于是 V18 之后删掉的账号又把邮箱扣在 uk_user_email 上：本人换账号回来、
-- 或者管理员想把这个邮箱挂到别人身上，都会撞唯一约束。
-- 实体那边已改成 ALWAYS，这里把存量的残留一次性放掉。
UPDATE app_user SET email = NULL WHERE deleted = TRUE AND email IS NOT NULL;
