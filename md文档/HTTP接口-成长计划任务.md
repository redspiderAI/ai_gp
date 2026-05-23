# HTTP 接口 — 成长计划任务执行（tasks 表）

与 **助手待办**（`user_assistant_tasks` + 每日提醒）分离：本组接口操作确认计划后写入的 **`tasks`** 表。

鉴权：`Authorization: Bearer {accessToken}`

---

## 1. 状态说明

| status | 含义 |
|--------|------|
| `PENDING` | 待执行（仅在该任务 `scheduledDate` 当天可开始） |
| `IN_PROGRESS` | 进行中（已调开始接口） |
| `COMPLETED` | 已完成（到 `plannedEndAt` 自动完成，或手动完成接口） |
| `SKIPPED` | 用户跳过（预留，当前无 C 端跳过接口） |
| `INCOMPLETE` | 未完成（计划日次日仍为 `PENDING`，或次日仍为 `IN_PROGRESS`） |

**计划结束时刻**：`plannedEndAt = startedAt + estimatedMinutes`（至少 1 分钟）。

**每日提醒**：仍由 `user_assistant_tasks`（`[学习计划] …`）在 `dailyReminderTime` 推送，不受本接口影响。

---

## 2. 查询任务

### 2.1 按日期

```http
GET /api/v1/users/me/growth-tasks?date=2026-05-22
```

| 参数 | 必填 | 格式 | 说明 |
|------|------|------|------|
| `date` | 是 | `yyyy-MM-dd` | 月、日须补零（如 `2026-05-22`，**不要**写 `2026-5-22`） |

非法格式返回 **400**（`BAD_REQUEST`），不再误报 500。

### 2.2 用户本地今天

```http
GET /api/v1/users/me/growth-tasks/today
```

响应 `tasks[]` 含 `plannedEndAt`，便于前端展示倒计时。

---

## 3. 开始执行

```http
POST /api/v1/users/me/growth-tasks/{taskId}/start
```

**条件**：

- 须为当前用户任务；
- `status` 必须为 `PENDING`；
- 用户本地日历日须等于该任务的 **`scheduledDate`（计划日）**：
  - **可以**：计划日当天任意时刻开始（如 08:00 的任务 06:00 即可点「开始」，不必等每日提醒）；
  - **不可以**：早于 `scheduledDate` 的日期开始（不能提前到昨天做明天的任务）；
  - **不可以**：计划日自然日结束后开始（次日及以后为 `INCOMPLETE` 或 409）。

**成功 200**：`status=IN_PROGRESS`，`startedAt` 有值，`plannedEndAt` 可展示。

**409**：非待执行、未到计划日、计划日已过等。

---

## 4. 提前完成

```http
POST /api/v1/users/me/growth-tasks/{taskId}/complete
Content-Type: application/json
```

在 **`plannedEndAt` 之前**结束进行中任务，或在计划日**不点「开始」直接标记完成**。请求体可省略或传 `{}`。

### 4.1 请求体（可选）

| 字段 | 类型 | 必填 | 约束 | 说明 |
|------|------|------|------|------|
| `actualMinutes` | integer | 否 | 1～1440 | 实际耗时（分钟） |
| `qualityScore` | integer | 否 | 1～5 | 自评质量 |

示例：

```json
{
  "actualMinutes": 25,
  "qualityScore": 4
}
```

### 4.2 条件

- 须为当前用户任务（JWT，非 Body 传 `userId`）；
- `status` 为 `PENDING` 或 `IN_PROGRESS`；
- 用户本地日历日须等于 **`scheduledDate`（计划日）**（与「开始执行」相同，不可跨日补完成）。

### 4.3 响应

**200**：`status=COMPLETED`，`completedAt` 为当前用户本地时刻；若曾开始则保留 `startedAt`、`plannedEndAt`。

**409**：已完成/已跳过/未完成、非计划日等。

**400**：`actualMinutes` / `qualityScore` 超出范围。

---

## 5. 服务端自动逻辑（无需前端调用）

每分钟与助手提醒同窗扫描：

1. `IN_PROGRESS` 且当前时间 ≥ `plannedEndAt` → `COMPLETED`
2. `IN_PROGRESS` 且 `scheduledDate` 早于用户本地今天 → `INCOMPLETE`
3. `PENDING` 且 `scheduledDate` 早于用户本地今天 → `INCOMPLETE`

配置：`app.growth-task.execution-enabled`（默认 `true`）。

---

## 6. 数据库迁移

已有库执行 `scripts/mysql-existing-database-changes.sql` 末尾：

- `tasks.started_at`
- `tasks.status` 增加 `IN_PROGRESS`、`INCOMPLETE`

OpenAPI：`docs/openapi/v1-growth-tasks.yaml`
