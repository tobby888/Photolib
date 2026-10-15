-- 登录按邮箱查找时，输入会被 trim + 转小写后再精确比对。V15 把当时的存量规范过一次，
-- 但旧系统迁移（LegacyMigrationService）之后一直把旧库的邮箱原样写进来：首尾带空格的
-- 那几条，MySQL 8 默认的 NO PAD 排序规则下和输入永远对不上，本人用正确的邮箱和密码也
-- 登录不了。迁移代码已改成入库前规范化，这里把已经进来的放正。
--
-- 空白邮箱等于没填，直接清掉（NULL 不占唯一索引）。
UPDATE app_user SET email = NULL
WHERE email IS NOT NULL AND TRIM(email) = '';

-- 只改"规范化之后全表只有它自己"的那些行：万一库里同时有 'a@b.com ' 和 'a@b.com'，
-- 硬改会撞 uk_user_email 让整次升级失败，这种留给管理员在账号编辑里手工处理。
-- 内层派生表带 GROUP BY，MySQL 会先物化它，不会触发"UPDATE 的目标表出现在子查询里"的 1093。
UPDATE app_user SET email = LOWER(TRIM(email))
WHERE email IS NOT NULL
  AND email <> LOWER(TRIM(email))
  AND LOWER(TRIM(email)) IN (
      SELECT normalized FROM (
          SELECT LOWER(TRIM(email)) AS normalized
          FROM app_user
          WHERE email IS NOT NULL
          GROUP BY LOWER(TRIM(email))
          HAVING COUNT(*) = 1
      ) AS unique_normalized
  );
