-- users 表增加 phone 字段，并从已有手机身份回填；历史仅资料绑定、无密码的手机身份同步邮箱密码以便登录。
-- 可重复执行。

SET @db = DATABASE();

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'users' AND COLUMN_NAME = 'phone') = 0,
    'ALTER TABLE users ADD COLUMN phone CHAR(11) DEFAULT NULL COMMENT ''已绑定手机号（与 user_identities.phone 同步）'' AFTER nickname',
    'SELECT ''skip phone column'' AS n'
);
PREPARE s FROM @sql;
EXECUTE s;
DEALLOCATE PREPARE s;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.STATISTICS
     WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'users' AND INDEX_NAME = 'uk_users_phone') = 0,
    'ALTER TABLE users ADD UNIQUE KEY uk_users_phone (phone)',
    'SELECT ''skip uk_users_phone'' AS n'
);
PREPARE s FROM @sql;
EXECUTE s;
DEALLOCATE PREPARE s;

UPDATE users u
INNER JOIN user_identities ui ON ui.user_id = u.id AND ui.identity_type = 'phone'
SET u.phone = ui.identifier
WHERE u.phone IS NULL;

UPDATE user_identities phone
INNER JOIN user_identities email ON email.user_id = phone.user_id AND email.identity_type = 'email'
SET phone.credential = email.credential
WHERE phone.identity_type = 'phone'
  AND phone.credential IS NULL
  AND email.credential IS NOT NULL;
