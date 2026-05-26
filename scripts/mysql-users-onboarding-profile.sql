-- 用户首次登录画像（年龄、职业、爱好、探索方向等）。在已有库执行一次，可重复执行。
-- 与 AppUser 字段：profile_age, profile_occupation, profile_hobbies, profile_exploration, onboarding_completed 对应。

SET @db = DATABASE();

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'users' AND COLUMN_NAME = 'profile_age') = 0,
    'ALTER TABLE users ADD COLUMN profile_age TINYINT UNSIGNED DEFAULT NULL COMMENT ''年龄（周岁，首次登录画像）'' AFTER language',
    'SELECT ''skip profile_age'' AS n'
);
PREPARE s FROM @sql;
EXECUTE s;
DEALLOCATE PREPARE s;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'users' AND COLUMN_NAME = 'profile_identity') > 0
    AND (SELECT COUNT(*) FROM information_schema.COLUMNS
         WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'users' AND COLUMN_NAME = 'profile_occupation') = 0,
    'ALTER TABLE users CHANGE COLUMN profile_identity profile_occupation VARCHAR(200) DEFAULT NULL COMMENT ''职业（首次登录画像）''',
    'SELECT ''skip rename profile_identity'' AS n'
);
PREPARE s FROM @sql;
EXECUTE s;
DEALLOCATE PREPARE s;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'users' AND COLUMN_NAME = 'profile_occupation') = 0,
    'ALTER TABLE users ADD COLUMN profile_occupation VARCHAR(200) DEFAULT NULL COMMENT ''职业（首次登录画像）'' AFTER profile_age',
    'SELECT ''skip profile_occupation'' AS n'
);
PREPARE s FROM @sql;
EXECUTE s;
DEALLOCATE PREPARE s;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'users' AND COLUMN_NAME = 'profile_hobbies') = 0,
    'ALTER TABLE users ADD COLUMN profile_hobbies TEXT DEFAULT NULL COMMENT ''爱好'' AFTER profile_occupation',
    'SELECT ''skip profile_hobbies'' AS n'
);
PREPARE s FROM @sql;
EXECUTE s;
DEALLOCATE PREPARE s;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'users' AND COLUMN_NAME = 'profile_exploration') = 0,
    'ALTER TABLE users ADD COLUMN profile_exploration TEXT DEFAULT NULL COMMENT ''希望探索的专业方向等'' AFTER profile_hobbies',
    'SELECT ''skip profile_exploration'' AS n'
);
PREPARE s FROM @sql;
EXECUTE s;
DEALLOCATE PREPARE s;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'users' AND COLUMN_NAME = 'onboarding_completed') = 0,
    'ALTER TABLE users ADD COLUMN onboarding_completed TINYINT(1) NOT NULL DEFAULT 0 COMMENT ''是否完成首次画像填写'' AFTER profile_exploration',
    'SELECT ''skip onboarding_completed'' AS n'
);
PREPARE s FROM @sql;
EXECUTE s;
DEALLOCATE PREPARE s;
