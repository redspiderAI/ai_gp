# AI 对话与助手任务 — 业务流程说明

本文档描述当前后端已实现的 **智能对话、助手待办、站内通知与任务提醒** 的业务流程，便于产品、测试与前端同学理解，**不依赖阅读 Java 代码**。  
接口字段与路径详见 **`md文档/HTTP接口-AI对话与通知.md`**；表结构见 **`md文档/数据库.md`** 第 13～16 节。

---

## 1. 功能边界（先分清三件事）

| 概念 | 是什么 | 用户在哪看到 |
|------|--------|--------------|
| **AI 对话会话** | 用户与智能助手的一路聊天记录（可多轮） | App 聊天页 |
| **助手任务** | 用户在对话里让 AI 记的**个人待办**（标题、说明、截止日期、完成/取消） | 任务列表 + 对话里由 AI 说明操作结果 |
| **成长计划任务**（`tasks` 表） | 目标拆解后排期的**每日学习任务** | 成长计划模块（与助手任务**不是同一张表**） |

助手任务由 AI 通过后端「工具」增删改查，**不会**自动写入成长计划的 `tasks` 表。

**成长计划草案（新）**：用户要「制定复习/学习计划」时，AI 调用 `propose_growth_plan` 生成**待确认**草案（表 `growth_plan_proposals`），对话响应中带 `planProposal.proposalId`。用户在 App 确认后，后端写入 `goals`/`plans`/`tasks`，并同步每日助手待办用于到点提醒。详见 **`md文档/HTTP接口-成长计划草案.md`**。

---

## 2. 用户主动发一条消息（主流程）

```mermaid
flowchart TD
    A[用户在 App 输入一句话] --> B[POST 发送对话]
    B --> C{带了 sessionId?}
    C -->|否| D[新建一场对话]
    C -->|是| E[继续已有对话]
    D --> F[可选：数据规划<br/>判断需要哪些上下文]
    E --> F
    F --> G[组装说明书：画像 / 历史 / 待办摘要]
    G --> H[可选：内部意图分析]
    H --> I[调用大模型生成回复]
    I --> J{涉及改任务?}
    J -->|是| K[模型调用工具<br/>查/增/改/取消任务，可多轮]
    J -->|否| L[直接文字回复]
    K --> L
    L --> M[仅保存：用户一句 + AI 最终一句]
    M --> N[WebSocket 推送 CHAT_REPLY]
    N --> O[返回 reply 与 sessionId]
```

### 2.1 分步说明

1. **鉴权**：须已登录，请求头带 `Authorization: Bearer {accessToken}`。
2. **会话**：首次不传 `sessionId` 会新建会话；后续把上次返回的 `sessionId` 原样带回即可续聊。会话标题默认取首条用户消息前 200 字。
3. **数据规划**（配置 `app.chat.multi-phase-enabled=true` 时，默认开启）：在正式回答前，后端会**额外调用一次**大模型（用户不可见），判断本轮是否需要：
   - 历史聊天记录
   - 用户画像（昵称、爱好、每周小时数等）
   - 待办任务列表摘要
   - 是否开放任务管理工具  
   规划失败时使用「全加载」的保守策略。  
   **快速路由**（`app.chat.fast-path-enabled=true`，默认开启）：问候、短句闲聊且不含待办/成长计划关键词时，**跳过规划 LLM**，仅启用 `chat`（续聊时加 `chat_history`），显著降低延迟。
4. **意图分析**（多阶段开启且本轮路由含 `assistant_tasks` 时）：再让大模型用几句话总结用户意图，**仅给正式回答参考**，不会原样展示给用户。纯闲聊路由会跳过本步。
5. **正式回答**：将「系统说明书 +（可选）历史 + 用户本轮输入」发给配置的 AI 提供商（默认 **mimo**，可指定 **ollama**）。
6. **工具**：大模型可多次调用后端工具（默认最多 5 轮），包括：
   - **助手待办**：`create_task` / `list_tasks` 等；
   - **计划草案**：`propose_growth_plan`（制定学习计划时，**须先出草案**，禁止批量 `create_task` 代替整份计划）。
   工具结果再喂回模型，最后输出面向用户的一段话；若生成了草案，HTTP 响应附带 `planProposal`。
7. **用户确认计划**（App 调用，非对话内）：`POST /api/v1/growth/plan-proposals/{id}/confirm` → 入库并开始每日 `dueAt` 提醒。
8. **落库规则**：数据库里**只存两条**——本轮用户消息、本轮助手最终回复。中间的规划、意图分析、工具调用过程**不写入** `ai_chat_messages`（计划草案存 `growth_plan_proposals`）。
9. **实时推送**：若客户端已连接 WebSocket，会收到 `type=CHAT_REPLY` 的 JSON（含会话 id、消息 id、正文预览等）。

### 2.2 相关配置（`application.yaml` / 环境变量）

| 配置 | 含义 | 默认 |
|------|------|------|
| `app.chat.default-provider` / `AI_CHAT_DEFAULT_PROVIDER` | 默认 AI 提供商 | `mimo` |
| `app.chat.multi-phase-enabled` / `AI_CHAT_MULTI_PHASE_ENABLED` | 是否启用规划 + 意图分析 | `true` |
| `app.chat.fast-path-enabled` / `AI_CHAT_FAST_PATH_ENABLED` | 闲聊是否跳过规划 LLM | `true` |
| `app.chat.max-history-messages` | 正式回答带入的历史条数上限 | `24` |
| `app.chat.max-tool-rounds` | 任务工具最多轮次 | `5` |
| `app.chat.push-on-reply-enabled` | 对话回复是否 WebSocket 推送 | `true` |
| `MIMO_API_KEY` | MiMo API Key | 必填（使用 mimo 时） |

---

## 3. 查看历史消息

- 接口：`GET /api/v1/ai/chat/sessions/{sessionId}/messages`
- 仅能查看**当前登录用户自己**的会话。
- 返回按时间**升序**的消息列表；每条为 `USER` 或 `ASSISTANT` 角色（与落库规则一致，不含内部工具消息）。

---

## 4. 助手任务与成长计划任务（AI 可执行的操作）

### 4.1 助手待办（`user_assistant_tasks`）

| 用户说法示例 | 后端行为 |
|--------------|----------|
| 「帮我记周五交报告」 | `create_task`，`status=OPEN`，可选 `dueDate` / `dueAt` |
| 「我有哪些没做的？」 | `list_tasks`，可按 `OPEN` / `DONE` / `CANCELLED` 筛选 |
| 「把 3 号任务标成完成」 | `update_task`，`status=DONE` |
| 「那个任务不要了」 | `delete_task`（`status=CANCELLED`） |

### 4.2 成长计划每日任务（`tasks` 表，用户确认计划后才有）

| 用户说法示例 | 后端行为 |
|--------------|----------|
| 「我今天的英语学习任务完成了」 | 先 `list_growth_tasks`（date 默认今天）+ `list_tasks` 匹配标题；**唯一**匹配则 `complete_growth_task` 或 `update_task(DONE)` |
| 多条候选 / 日期不明 | **必须追问用户**，禁止瞎猜 taskId |
| 用户明确「昨天」「5月20号」 | `date` / `dueFrom` / `dueTo` 用对应日期，非今天 |

`complete_growth_task` 成功后会同步将同日 `[学习计划] {标题}` 助手待办置为 `DONE`。

用户也可不经过 AI，直接调用 **`GET /api/v1/users/me/tasks`** 或 **`GET /api/v1/users/me/growth-tasks/today`**。

---

## 5. 定时任务提醒（用户未主动说话，精确到分）

```mermaid
flowchart LR
    T[每 30 秒 cron + 近期到点一次性调度] --> L[scheduler_lock 单飞]
    L --> U[粗筛 OPEN 任务候选]
    U --> Z{用户本地 due_at 已到?}
    Z -->|否| W[跳过]
    Z -->|是| V{app.task-reminder 开?}
    V -->|否| W
    V -->|是| X[最新用户会话（或兜底「任务提醒」）+ 站内通知 + WS]
```

- **触发**：
  - `AssistantTaskReminderScheduler`，cron 默认 **`0/30 * * * * ?`**（每 30 秒，时区见 `app.task-reminder.zone`）。
  - **`AssistantTaskNearDueScheduleService`**：创建/更新带 `due_at` 的任务后，为 48 小时内到期的任务注册**一次性**调度（如「3 分钟后提醒我」），到点即触发，不依赖整分钟 tick；服务重启后由 `AssistantTaskReminderStartupRunner` 补注册。
- **到点规则**（`daily_task_reminder` 库字段保留但**代码侧恒为开启**，不再拦截推送）：
  - 有 **`due_at`**（`yyyy-MM-dd HH:mm`，用户本地）：当前用户当地时间 ≥ `due_at` 且尚未针对该次到期发过提醒 → 发送。
  - 仅有 **`due_date`**：在截止日当天 **`app.task-reminder.default-due-date-reminder-time`**（默认 `08:00`）发送。
- **扫描**：每 30 秒 cron；`due_at` 在「近 36 小时～未来 48 小时」内粗筛；**已逾期且 `reminder_sent_at` 为空**的任务不受 36 小时下限限制（启动/补发）。
- **启动补发**：应用启动后立即执行一次与 cron 相同的扫描，并为近期 `due_at` 任务补注册一次性调度（`AssistantTaskReminderStartupRunner`）。
- **幂等**：`reminder_sent_at` 不早于本次到期时刻则不再重复发；用户修改 `due_at` 后可再次提醒。
- **多实例**：`scheduler_lock` 表互斥，避免重复投递。
- **每日 8 点摘要**（用户本地 `default-due-date-reminder-time`，默认 `08:00`）：
  - 一条合并消息：**鼓励语** + **当日待办**（`tasks` 表 PENDING/IN_PROGRESS + 助手 OPEN 任务，学习计划助手待办与成长任务去重）。
  - **周六**：在同一条消息末尾追加 **【本周回顾】**（`user_companion_memory.pending_digest_text`）；不再单独推「本周回顾」会话（若摘要已成功投递）。
  - 幂等：`user_notification_settings.daily_briefing_last_sent_date`。
  - 当日 `08:00` 到点的助手任务**不再**逐条重复推送；其他时刻的 `due_at` 仍按单任务提醒。
- **单任务到点**（非 8 点摘要覆盖的场景）：模板文案（非现场调大模型）。
- **会话**：写入用户 **最近活跃的非系统会话**（按 `updated_at`）；若从未对话则落入 **「任务提醒」** 兜底会话。消息角色为 `ASSISTANT`。
- **通知**：`user_in_app_notifications` + WebSocket（`TASK_DUE_REMINDER`）；同时推送 `CHAT_REPLY` 便于聊天页实时刷新。
- **排查日志**：`app.task-reminder.debug-log-enabled=true` 时写入 `logs/task-reminder.txt`（每 tick 候选数、跳过原因、投递结果、近期到点调度注册/触发）。

相关配置：`enabled`、`in-app-enabled`、`push-enabled`、`cron`、`zone`、`default-due-date-reminder-time`。  
数据库：`scripts/mysql-scheduler-lock.sql`、`scripts/mysql-user-assistant-tasks-reminder-index.sql`（或 `run-mysql-migrations.py`）。

---

## 5.5 陪伴记忆与每周回顾（周六 03:00 总结，08:00 推送）

```mermaid
flowchart TD
    S[周六 03:00 cron] --> L[scheduler_lock 单飞]
    L --> U[扫描近 7 天有对话的用户]
    U --> W1[LLM：先总结本周]
    W1 --> W2[LLM：合并长期记忆 分层压缩]
    W2 --> DB[(user_companion_memory)]
    M[每分钟 cron] --> D{用户本地周六 08:00?}
    D -->|是| P[weekly_companion_digest 开?]
    P -->|是| N[「本周回顾」会话 + 站内通知 + WS]
    DB --> W1
    DB --> N
```

### 分步说明

1. **总结（周六凌晨，默认 03:00，`app.companion-memory.summarize-cron`）**  
   - 读取用户近 7 天对话（排除「任务提醒」「本周回顾」系统会话）及本周任务变更统计。  
   - **第一步**：生成本周内部总结 + 给用户看的 `pending_digest_text`。  
   - **第二步**：结合旧 `memory_text` 与画像，**重写**长期记忆（非无限追加）：最近几周较详细；1～2 月前保留里程碑；更早仅事件名+日期段；锻炼/学习/开会等例行活动按月/周汇总次数或时长。  
   - 写入 `user_companion_memory`，周键 `summarized_week_key`（如 `2026-W20`）幂等。

2. **对话注入**  
   - 每轮 `POST /api/v1/ai/chat` 在 system 中附带 `memory_text`（与本轮用户说法冲突时以本轮为准）。

3. **推送（与任务提醒同一分钟 tick）**  
   - 用户本地 **周六**、时刻 **`digest-delivery-time`（默认 08:00）**：优先并入 **每日任务摘要**（见 §5）；仅当当日摘要未投递时，才回退为单独投递至 **「本周回顾」** 会话，通知类型 `WEEKLY_COMPANION_DIGEST`。

### 相关配置

| 配置 | 含义 | 默认 |
|------|------|------|
| `app.companion-memory.enabled` | 是否启用周总结 | `true` |
| `app.companion-memory.summarize-cron` | 总结 cron | `0 0 3 ? * SAT` |
| `app.companion-memory.digest-delivery-time` | 用户本地推送时刻 | `08:00` |
| `app.companion-memory.max-messages-per-week` | 单周纳入总结的消息上限 | `200` |
| `app.companion-memory.max-memory-chars` | 长期记忆最大字符 | `12000` |

数据库：`scripts/mysql-user-companion-memory.sql` 或 `scripts/mysql-existing-database-changes.sql`。

---

## 6. WebSocket 实时推送

| 项目 | 说明 |
|------|------|
| 地址 | `ws://{host}/ws/v1/chat`（生产用 `wss://`） |
| 鉴权 | 握手时 Query `token={accessToken}`，或 Header `Authorization: Bearer {accessToken}` |
| 方向 | **仅服务端 → 客户端**；客户端上行可忽略（可发 ping） |

### 6.1 推送 JSON 类型

**对话回复**（`app.chat.push-on-reply-enabled=true`）：

```json
{
  "type": "CHAT_REPLY",
  "sessionId": 1,
  "messageId": 42,
  "contentPreview": "好的，已为你创建…",
  "unreadCount": 0
}
```

**任务到期提醒**（`app.task-reminder.push-enabled=true`）：

```json
{
  "type": "TASK_DUE_REMINDER",
  "notificationId": 10,
  "sessionId": 2,
  "messageId": 55,
  "taskId": 7,
  "title": "今日待办：完成周报",
  "body": "今天（2026-05-16）有一项待办…",
  "unreadCount": 1
}
```

**每周陪伴回顾**（`app.companion-memory.digest-push-enabled=true`）：

```json
{
  "type": "WEEKLY_COMPANION_DIGEST",
  "notificationId": 11,
  "sessionId": 3,
  "messageId": 60,
  "taskId": null,
  "title": "本周回顾 · 2026-W20",
  "body": "这一周你…",
  "unreadCount": 2
}
```

`unreadCount` 为当前用户**站内通知**未读条数（不含聊天已读状态）。

### 6.2 移动系统推送（uni-push 2.0，后台/杀进程）

| 项目 | 说明 |
|------|------|
| 设备注册 | `PUT /api/v1/users/me/push-tokens`；`token` = `uni.getPushClientId()` |
| 触发 | 站内通知同时，在 `mobile-push.enabled` + `unipush-cloud-url` 配置时 POST 云函数 URL |
| Firebase | 在 **DCloud 开发者中心** 托管配置，不由 Java 直连 |
| 与 WS 关系 | WS = 在线即时；uni-push = 系统通知栏，**互不替代** |
| 云函数示例 | `scripts/uni-push-cloud-function/index.js` |

---


## 7. 站内通知

- 列表 / 未读数 / 标记已读：见 **`md文档/HTTP接口-AI对话与通知.md`** 第 4 章。
- 与对话的关系：通知可携带 `sessionId`、`messageId`，便于点击跳转到对应会话与消息。

---

## 8. 数据库脚本（已有库升级）

按顺序在 MySQL 执行（仓库 `scripts/` 目录）：

1. `mysql-ai-chat.sql` — 会话、消息、助手任务表  
2. `mysql-ai-chat-task-reminder.sql` — 任务表增加 `reminder_sent_at`（若已含于建表脚本可跳过）  
3. `mysql-user-assistant-tasks-due-at.sql` — `due_at` 列（若建表已含可跳过）  
4. `mysql-in-app-notifications.sql` — 站内通知表  
5. `mysql-scheduler-lock.sql`、`mysql-user-assistant-tasks-reminder-index.sql` — 提醒单飞锁与扫描索引  
6. `mysql-user-companion-memory.sql` 或 `mysql-existing-database-changes.sql` — 陪伴记忆表与通知开关列  

应用使用 `ddl-auto: validate` 时，须先执行脚本再启动。

---

## 9. 主要代码入口（供开发查阅）

| 职责 | 类 |
|------|-----|
| 对话编排 | `AiChatService` |
| 数据规划 / 意图分析 | `AiChatPlanningService` |
| 用户上下文组装 | `AiChatUserContextBuilder` |
| 任务工具执行 | `AiChatToolExecutor` |
| 大模型 HTTP 客户端 | `OpenAiCompatibleChatClient` |
| 到期提醒 | `AssistantTaskReminderService`、`AssistantTaskReminderScheduler` |
| 近期到点调度 | `AssistantTaskNearDueScheduleService` |
| 启动补发 | `AssistantTaskReminderStartupRunner` |
| 陪伴记忆周总结 | `CompanionMemoryService`、`CompanionMemoryScheduler` |
| 站内通知 + 推送 | `InAppNotificationService`、`ChatRealtimePushService` |

---

## 10. 文档维护

业务流程或配置变更时，请同步更新：

- 本文件（`md文档/AI对话与助手任务-流程说明.md`）
- `md文档/HTTP接口-AI对话与通知.md`
- `md文档/数据库.md`（表结构章节）
- `docs/openapi/v1-ai-chat.yaml`（若存在 OpenAPI 约定）
