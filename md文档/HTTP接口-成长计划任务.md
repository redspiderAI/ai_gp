# HTTP 接口 — 成长计划任务查询（tasks + user_assistant_tasks）

鉴权：`Authorization: Bearer {accessToken}`

任务数据仅来自两张表：**`tasks`**（成长计划）与 **`user_assistant_tasks`**（助手待办）。

**标记完成**仅两种方式：

1. **对话**：如「我的英语复习完了」→ AI 调用工具  
2. **REST**：`POST /api/v1/users/me/tasks/complete`（见下文 §3）

---

## 1. 状态说明（tasks 表）

| status | 含义 |
|--------|------|
| `PENDING` | 待完成 |
| `IN_PROGRESS` | 历史遗留进行中（已无「开始」接口，可标记完成） |
| `COMPLETED` | 已完成 |
| `INCOMPLETE` | 计划日已过仍未完成（服务端自动） |

助手待办：`OPEN` → 完成时 `DONE`；`CANCELLED` 不可完成。

---

## 2. 查询任务

### 2.1 按日期

```http
GET /api/v1/users/me/growth-tasks?date=2026-05-22
```

| 参数 | 必填 | 说明 |
|------|------|------|
| `date` | 否 | `yyyy-MM-dd`；省略 = 用户时区今天 |

响应：`tasks[]`（成长计划）+ `assistantTasks[]`（当日助手待办）。

### 2.2 今天

```http
GET /api/v1/users/me/growth-tasks/today
```

### 2.3 助手待办全量列表

```http
GET /api/v1/users/me/tasks?status=OPEN
```

---

## 3. 标记已完成（统一接口）

```http
POST /api/v1/users/me/tasks/complete
Content-Type: application/json

{
  "source": "growth",
  "taskId": 12
}
```

| 字段 | 必填 | 说明 |
|------|------|------|
| `source` | 是 | `assistant` 或 `growth` |
| `taskId` | 是 | 须属于当前 JWT 用户 |

| source | 表 | 允许的前状态 | 完成后 |
|--------|-----|-------------|--------|
| `assistant` | `user_assistant_tasks` | `OPEN` | `DONE` |
| `growth` | `tasks` | `PENDING`、`IN_PROGRESS` | `COMPLETED`（须在 `scheduledDate` 当天） |

**200**：`CompleteUserTaskResponse`（含 `assistantTask` 或 `growthTask`）。

**409**：已完成、已取消、或状态不允许。

`source=growth` 成功时，会同步将同日 `[学习计划] {标题}` 助手待办标为 `DONE`（若存在）。

---

## 4. 已移除的接口

以下接口**已删除**，请勿再调用：

- ~~`POST /api/v1/users/me/growth-tasks/{taskId}/start`~~
- ~~`POST /api/v1/users/me/growth-tasks/{taskId}/complete`~~

---

## 5. 服务端自动逻辑（无需前端调用）

每分钟扫描：`PENDING`/`IN_PROGRESS` 且计划日已过的 `tasks` → `INCOMPLETE`；历史 `IN_PROGRESS` 到 `plannedEndAt` 可自动 `COMPLETED`。

OpenAPI：`docs/openapi/v1-growth-tasks.yaml`（查询）、`docs/openapi/v1-users.yaml`（完成）
