# HTTP 接口 — 成长计划草案（确认后入库）

AI 在对话中调用 `propose_growth_plan` 生成**待确认**草案；用户在前端查看详情并确认后，后端写入 `goals` / `plans` / `tasks`，并为每天创建助手待办（`dueAt`）以触发**每日提醒**。

鉴权：所有接口需 `Authorization: Bearer {accessToken}`。

> **Swagger 说明**：若文档里出现名为 `user` 的 query 参数（含 `userId`、`uid` 等），属于展示错误，**前端不要传**。当前用户由服务端从 Bearer 令牌解析；客户端只需传路径里的 `proposalId` 并在 Header 带登录后获得的 `accessToken`。

---

## 1. 与对话的配合

### 1.1 发送消息（已有）

`POST /api/v1/ai/chat`

当用户说「帮我制定一个月六级复习计划」等，响应除 `reply` 外可能包含：

| 字段 | 说明 |
|------|------|
| `planProposal` | 非空表示本轮已保存草案，见下表 |
| `planProposal.proposalId` | 用于拉详情 / 确认 / 拒绝 |

前端建议：展示 `reply` + 计划卡片（标题、天数、日期范围），按钮「查看详情」「确认计划」「暂不采用」。

### 1.2 草案 JSON 结构（`payload_json` / 工具入参）

`version` 固定为 `1`：

```json
{
  "version": 1,
  "goalTitle": "一个月通过大学英语六级",
  "goalDescription": "可选",
  "domain": "LANGUAGE",
  "deadline": "2026-06-19",
  "summary": "给用户看的 2～6 句总述",
  "dailyReminderTime": "08:00",
  "days": [
    {
      "dayIndex": 1,
      "scheduledDate": "2026-05-20",
      "title": "核心词汇 + 听力精听",
      "description": "可选",
      "estimatedMinutes": 90
    }
  ]
}
```

| 字段 | 约束 |
|------|------|
| `domain` | `SKILLS` / `CERTIFICATION` / `LANGUAGE` / `SOFT_SKILLS` / `SIDE_HUSTLE`，默认 `LANGUAGE` |
| `days` | 1～31 条；`scheduledDate` 按 `dayIndex` 非递减 |
| `estimatedMinutes` | 15～480 |
| `dailyReminderTime` | `HH:mm`，默认 `08:00` |

---

## 2. 获取草案详情

`GET /api/v1/growth/plan-proposals/{proposalId}`

- `proposalId`：路径参数，须 > 0
- 仅可访问**当前用户**自己的草案

响应体：`GrowthPlanProposalDetailResponse`（含 `days` 数组、`status`、`expiresAt` 等）。

---

## 3. 确认草案（入库 + 每日提醒）

`POST /api/v1/growth/plan-proposals/{proposalId}/confirm`

**成功时：**

1. 创建 `goals`（状态 `ACTIVE`）、`plans`（新版本 `ACTIVE`）
2. 按天写入 `tasks`（成长计划任务）
3. 按天写入 `user_assistant_tasks`，标题前缀 `[学习计划]`，`dueAt` = 当日 `dailyReminderTime`（用于现有任务提醒调度）
4. 草案状态改为 `CONFIRMED`

响应示例字段：`goalId`、`planId`、`growthTaskCount`、`reminderTaskCount`、`message`。

**错误：**

| HTTP | 场景 |
|------|------|
| 404 | 草案不存在或非本人 |
| 409 | 已确认/已拒绝，或已超过 `expiresAt`（默认创建后 7 天） |

用户需在通知设置中开启**每日任务提醒**（`daily_task_reminder`），否则到点不会推送。

确认后 **`tasks` 表**任务的「开始执行 / 自动完成 / 跨日未完成」见 **`md文档/HTTP接口-成长计划任务.md`**（与助手每日提醒 `user_assistant_tasks` 分离）。

---

## 4. 拒绝草案

`POST /api/v1/growth/plan-proposals/{proposalId}/reject`

- 将状态置为 `REJECTED`，不写入 goals/plans/tasks
- 已处理的草案再次拒绝返回 409

---

## 5. 数据库

已有库执行：`scripts/mysql-existing-database-changes.sql` 中 `growth_plan_proposals` 段。

OpenAPI：`docs/openapi/v1-growth-plan-proposals.yaml`。
