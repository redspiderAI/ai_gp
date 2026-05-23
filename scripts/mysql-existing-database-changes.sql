-- 在已有数据库上增量执行（本仓库仅维护此增量脚本 + 全量建库脚本）
-- 执行前请备份。可重复执行时使用下方「若列已存在」类语句需手工判断是否跳过。

-- 用户陪伴记忆表
CREATE TABLE IF NOT EXISTS user_companion_memory (
    user_id                 BIGINT UNSIGNED NOT NULL COMMENT 'users.id',
    memory_text             MEDIUMTEXT      DEFAULT NULL COMMENT '合并后的长期记忆（注入对话 system）',
    week_internal_summary   TEXT            DEFAULT NULL COMMENT '最近一次本周内部总结（供排查）',
    pending_digest_text     TEXT            DEFAULT NULL COMMENT '待周六早推送的用户可见本周回顾',
    summarized_week_key     VARCHAR(16)     DEFAULT NULL COMMENT '已完成总结的周键，如 2026-W20',
    digest_delivered_week_key VARCHAR(16)   DEFAULT NULL COMMENT '已推送回顾的周键',
    last_summarized_at      DATETIME        DEFAULT NULL,
    created_at              DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at              DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id),
    CONSTRAINT fk_companion_memory_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户陪伴记忆';

-- MySQL 8.0.12 以下无 IF NOT EXISTS on ADD COLUMN，若报错请手工检查列是否存在
ALTER TABLE user_notification_settings
    ADD COLUMN weekly_companion_digest TINYINT(1) NOT NULL DEFAULT 1
        COMMENT '是否接收每周六陪伴回顾' AFTER daily_task_reminder;

ALTER TABLE user_notification_settings
    ADD COLUMN daily_briefing_last_sent_date DATE DEFAULT NULL
        COMMENT '用户本地日：上次每日任务摘要投递日期' AFTER weekly_companion_digest;

-- AI 成长计划草案（用户确认前不落 goals/plans/tasks）
CREATE TABLE IF NOT EXISTS growth_plan_proposals (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id         BIGINT UNSIGNED NOT NULL COMMENT 'users.id',
    session_id      BIGINT UNSIGNED DEFAULT NULL COMMENT '来源 AI 对话会话',
    status          ENUM('PENDING','CONFIRMED','REJECTED') NOT NULL DEFAULT 'PENDING',
    payload_json    JSON            NOT NULL COMMENT '结构化计划，见 GrowthPlanProposalPayload version=1',
    goal_id         BIGINT UNSIGNED DEFAULT NULL COMMENT '确认后关联 goals.id',
    plan_id         BIGINT UNSIGNED DEFAULT NULL COMMENT '确认后关联 plans.id',
    expires_at      DATETIME        DEFAULT NULL COMMENT '草案过期时间',
    confirmed_at    DATETIME        DEFAULT NULL,
    rejected_at     DATETIME        DEFAULT NULL,
    created_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_gpp_user_status (user_id, status),
    CONSTRAINT fk_gpp_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='成长计划 AI 草案';

-- uni-push 2.0 设备 clientId（列名 fcm_token 历史兼容；存 push_clientid）
CREATE TABLE IF NOT EXISTS user_push_devices (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id     BIGINT UNSIGNED NOT NULL COMMENT 'users.id',
    device_id   VARCHAR(64)     NOT NULL COMMENT '客户端设备标识',
    platform    ENUM('ANDROID','IOS') NOT NULL,
    fcm_token   VARCHAR(512)    NOT NULL COMMENT 'uni.getPushClientId 返回值',
    created_at  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_push_user_device (user_id, device_id),
    KEY idx_push_user (user_id),
    CONSTRAINT fk_push_device_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户 FCM 推送设备';

-- 成长计划 tasks：开始执行、进行中自动完成、跨日未完成
ALTER TABLE tasks
    ADD COLUMN started_at DATETIME DEFAULT NULL COMMENT '用户开始执行时刻' AFTER completed_at;

ALTER TABLE tasks
    MODIFY COLUMN status ENUM('PENDING','IN_PROGRESS','COMPLETED','SKIPPED','INCOMPLETE') NOT NULL DEFAULT 'PENDING';
