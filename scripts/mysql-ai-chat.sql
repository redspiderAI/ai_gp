-- AI 对话与助手任务表（在已有库执行一次；可重复执行，已存在则跳过）

CREATE TABLE IF NOT EXISTS ai_chat_sessions (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id     BIGINT UNSIGNED NOT NULL COMMENT 'users.id',
    title       VARCHAR(200)    DEFAULT NULL COMMENT '会话标题（首条消息摘要）',
    provider    VARCHAR(32)     NOT NULL COMMENT 'mimo / ollama',
    model       VARCHAR(64)     NOT NULL COMMENT '实际调用的模型名',
    created_at  DATETIME        DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME        DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_user_updated (user_id, updated_at),
    CONSTRAINT fk_chat_sessions_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI 对话会话';

CREATE TABLE IF NOT EXISTS ai_chat_messages (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    session_id  BIGINT UNSIGNED NOT NULL,
    role        VARCHAR(20)     NOT NULL COMMENT 'USER/ASSISTANT/SYSTEM/TOOL',
    content     TEXT            DEFAULT NULL COMMENT '文本内容',
    tool_name   VARCHAR(64)     DEFAULT NULL COMMENT 'TOOL 角色时的工具名',
    tool_call_id VARCHAR(64)    DEFAULT NULL COMMENT 'TOOL 角色关联的 call id',
    round_action VARCHAR(32)     DEFAULT NULL COMMENT 'ASSISTANT 消息本轮动作（与 POST /ai/chat roundAction 一致）',
    created_at  DATETIME        DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_session_created (session_id, created_at),
    CONSTRAINT fk_chat_messages_session FOREIGN KEY (session_id) REFERENCES ai_chat_sessions(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI 对话消息';

CREATE TABLE IF NOT EXISTS user_assistant_tasks (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id     BIGINT UNSIGNED NOT NULL,
    title       VARCHAR(200)    NOT NULL,
    description VARCHAR(2000)   DEFAULT NULL,
    status      VARCHAR(20)     NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN/DONE/CANCELLED',
    due_date    DATE            DEFAULT NULL,
    due_at      DATETIME        DEFAULT NULL COMMENT '截止时刻（用户本地，精确到分）',
    reminder_sent_at DATETIME   DEFAULT NULL COMMENT '最近一次到期提醒发送时间',
    created_at  DATETIME        DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME        DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_user_status (user_id, status),
    CONSTRAINT fk_assistant_tasks_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI 助手管理的用户任务';
