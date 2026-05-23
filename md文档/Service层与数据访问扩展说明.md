# Service 层与数据访问扩展说明

本文档对应一次迭代中新增与修改的代码，依据 **`md文档/数据库.md`** 的表设计与关系编写。说明粒度到 **类 / 方法（函数）**，便于后续接 REST 或联调时查阅。

---

## 1. 新增与修改的文件一览

| 类型 | 路径 |
|:---|:---|
| 异常 | `src/main/java/com/aigp/demo/exception/NotFoundException.java` |
| 异常 | `src/main/java/com/aigp/demo/exception/ConflictException.java` |
| 工具 | `src/main/java/com/aigp/demo/support/UidGenerator.java` |
| Service | `src/main/java/com/aigp/demo/service/*.java`（共 12 个类） |
| Repository 扩展 | `GoalRepository`、`MilestoneRepository`、`PlanRepository`、`TaskRepository`、`AiPromptRepository`、`AiFeedbackRepository`、`UserSessionRepository`、`UserIdentityRepository` |

---

## 2. 异常类（`com.aigp.demo.exception`）

### 2.1 `NotFoundException`

继承 `RuntimeException`，表示**资源不存在或无权以该身份访问**（部分方法对「非本人数据」也抛出同一异常，避免泄露存在性）。

| 函数 | 作用 |
|:---|:---|
| `NotFoundException(String message)` | 构造异常，携带可读说明（如 `"User not found: id=..."`）。 |

### 2.2 `ConflictException`

继承 `RuntimeException`，表示**业务规则冲突**（如身份已被他人绑定、任务状态不允许再改、目标数量超限等）。

| 函数 | 作用 |
|:---|:---|
| `ConflictException(String message)` | 构造异常，携带可读说明。 |

---

## 3. 工具类（`com.aigp.demo.support`）

### 3.1 `UidGenerator`（`final`，不可实例化）

用于生成 **`users.uid`**（文档中为 `CHAR(16)` 的对外用户标识）。

| 函数 | 作用 |
|:---|:---|
| `private UidGenerator()` | 禁止 `new`，仅使用静态方法。 |
| `public static String nextUid()` | 使用 `SecureRandom` 生成 8 字节随机数，转为十六进制后取前 15 位，再前缀 `U` 并转大写，得到 **总长 16** 的字符串（形如 `U0123456789ABCDE`）。 |

---

## 4. Repository 扩展（Spring Data 派生查询）

以下方法由 Spring Data JPA **按方法名自动生成查询**，无手写 `@Query`。

### 4.1 `GoalRepository`

| 函数 | 作用 |
|:---|:---|
| `List<Goal> findByUser_IdOrderByUpdatedAtDesc(Long userId)` | 按用户主键查询其所有目标，按 `updated_at` **降序**（最近更新的在前）。 |
| `Optional<Goal> findByIdAndUser_Id(Long id, Long userId)` | 同时匹配目标 `id` 与所属用户 `user_id`，用于**归属校验**（本人目标）。 |
| `long countByUser_IdAndStatusIn(Long userId, Collection<GoalStatus> statuses)` | 统计某用户在给定状态集合内的目标数量（用于「并发管线」上限）。 |

### 4.2 `MilestoneRepository`

| 函数 | 作用 |
|:---|:---|
| `List<Milestone> findByGoal_IdOrderBySortOrderAsc(Long goalId)` | 某目标下全部里程碑，按 `sort_order` **升序**。 |
| `Optional<Milestone> findByIdAndGoal_Id(Long id, Long goalId)` | 按里程碑主键与所属目标联合查询（可用于校验里程碑属于某目标）。 |

### 4.3 `PlanRepository`

| 函数 | 作用 |
|:---|:---|
| `List<Plan> findByGoal_IdAndStatus(Long goalId, PlanStatus status)` | 某目标下指定状态（如 `ACTIVE`）的全部计划版本。 |
| `Optional<Plan> findTopByGoal_IdOrderByVersionDesc(Long goalId)` | 该目标下 **version 最大** 的一条计划（不限状态），用于计算下一版本号。 |
| `Optional<Plan> findFirstByGoal_IdAndStatusOrderByVersionDesc(Long goalId, PlanStatus status)` | 该目标下、指定状态中 **version 最大** 的一条（典型用法：取当前生效的 `ACTIVE` 计划）。 |

### 4.4 `TaskRepository`

| 函数 | 作用 |
|:---|:---|
| `List<Task> findByUser_IdAndScheduledDateOrderByCreatedAtAsc(Long userId, LocalDate scheduledDate)` | 某用户在指定**计划执行日**下的任务列表，按 `created_at` **升序**。 |
| `Optional<Task> findByIdAndUser_Id(Long id, Long userId)` | 按任务主键与冗余的 `user_id` 联合查询，用于**本人任务**操作。 |

### 4.5 `AiPromptRepository`

| 函数 | 作用 |
|:---|:---|
| `Optional<AiPrompt> findFirstByTypeAndActiveTrueOrderByUpdatedAtDesc(PromptType type)` | 某 `PromptType` 下，`is_active == true` 的提示词中，取 **`updated_at` 最新** 的一条（生产生效版本）。 |

### 4.6 `AiFeedbackRepository`

| 函数 | 作用 |
|:---|:---|
| `List<AiFeedback> findByUser_IdOrderByGeneratedAtDesc(Long userId)` | 某用户的 AI 反馈列表，按 `generated_at` **降序**（新的在前）。 |
| `Optional<AiFeedback> findByIdAndUser_Id(Long id, Long userId)` | 反馈主键与用户联合查询，用于本人标记已读。 |
| `long countByUser_IdAndReadByUserFalse(Long userId)` | 某用户 **`read_by_user` 为 false** 的反馈条数（未读数）。 |

### 4.7 `UserSessionRepository`

| 函数 | 作用 |
|:---|:---|
| `Optional<UserSession> findByUser_IdAndDeviceId(Long userId, String deviceId)` | 按用户 + 设备唯一键查找会话行，用于登录 upsert。 |

### 4.8 `UserIdentityRepository`

| 函数 | 作用 |
|:---|:---|
| `List<UserIdentity> findByUser_Id(Long userId)` | 列出某用户已绑定的全部身份，用于清除「主身份」标记等。 |

（`findByIdentityTypeAndIdentifier` 为原有方法，本迭代在 Service 中加强使用。）

---

## 5. Service 层（`com.aigp.demo.service`）

所有 Service 使用 **`@Service`**、**构造器注入**（Lombok `@RequiredArgsConstructor`）。读写事务通过 **`@Transactional`** / **`@Transactional(readOnly = true)`** 标注在类或方法上。

---

### 5.1 `AppUserService`

依赖：`AppUserRepository`、`UserNotificationSettingsService`。

| 函数 | 作用 |
|:---|:---|
| `AppUser requireById(Long id)` | 按主键查用户；不存在则抛 `NotFoundException`。只读事务。 |
| `AppUser requireByUid(String uid)` | 按对外 `uid` 查用户；不存在则抛 `NotFoundException`。只读事务。 |
| `AppUser registerNewUser(String nickname)` | **注册最小用户**：生成 `uid`、默认 `status=1`、`timezone`、`language`、`weekly_hours=0`；若 `nickname` 有内容则写入昵称，否则昵称默认为「新用户」；保存后再调用通知设置服务的 `getOrCreate` 保证存在 **`user_notification_settings`** 一行。写事务。 |
| `AppUser updateProfile(Long userId, String nickname, String avatarUrl, Integer weeklyHours)` | 按 `userId` 加载用户后，对非 `null` 的 `nickname`、`avatarUrl`、`weeklyHours` 做更新；`weeklyHours` 须在 **0–40**，否则 `IllegalArgumentException`。写事务。 |

---

### 5.2 `UserNotificationSettingsService`

依赖：`UserNotificationSettingsRepository`。

| 函数 | 作用 |
|:---|:---|
| `UserNotificationSettings getOrCreate(AppUser user)` | 若该用户已有通知设置则返回；否则新建一行（实体上布尔开关有默认值）并保存。写事务。 |
| `UserNotificationSettings update(AppUser user, boolean dailyTaskReminder, boolean conflictAlert, boolean milestoneCelebration, boolean laggingWarning)` | 先 `getOrCreate`，再批量写入四个开关字段并返回（由 JPA 脏检查落库）。写事务。 |

---

### 5.3 `UserIdentityService`

依赖：`UserIdentityRepository`。

| 函数 | 作用 |
|:---|:---|
| `Optional<UserIdentity> find(IdentityType identityType, String identifier)` | 按认证类型 + 标识符查询一条身份（如微信 openid）。只读事务。 |
| `UserIdentity linkIdentity(AppUser user, IdentityType identityType, String identifier, String credential, boolean primary, LocalDateTime verifiedAt)` | **绑定或更新身份**：若 `(identity_type, identifier)` 已存在且属于**其他用户**，抛 `ConflictException`；若已属于**当前用户**，可更新 `credential`、`verified_at`、在 `primary==true` 时把该条设为主并清除同用户其他条目的主标记；若不存在则插入新行。主身份互斥通过 `clearPrimaryForUser` 实现。写事务。 |
| `private void clearPrimaryForUser(Long userId)` | 将该用户下所有 `UserIdentity` 的 `is_primary` 置为 `false`，为即将设置的主身份腾位。 |

---

### 5.4 `UserSessionService`

依赖：`UserSessionRepository`。

| 函数 | 作用 |
|:---|:---|
| `UserSession upsertSession(AppUser user, String deviceId, String refreshToken, LocalDateTime expiresAt, String ipAddress, String accessTokenJti, boolean trusted)` | 按 `(user_id, device_id)` **查找或新建**会话行，写入刷新令牌、过期时间、IP、JTI、信任标记，并将 `revoked_at` 置空（表示重新有效）。写事务。 |
| `void revokeSession(Long userId, Long sessionId)` | 按 `sessionId` 加载会话，且会话必须属于 `userId`；否则 `NotFoundException`。将 `revoked_at` 设为当前时间（软撤销）。写事务。 |

---

### 5.5 `GoalService`

依赖：`GoalRepository`。

| 常量 / 集合 | 作用 |
|:---|:---|
| `MAX_CONCURRENT_PIPELINE_GOALS`（值为 `5`） | 与数据库文档中「单用户并发目标规模」一致的上限常量。 |
| `PIPELINE_STATUSES` | `DECOMPOSING`、`PENDING`、`ACTIVE` 三种状态，用于计数「占用管线名额」的目标。 |

| 函数 | 作用 |
|:---|:---|
| `List<Goal> listForUser(Long userId)` | 返回该用户全部目标，按更新时间降序。只读事务。 |
| `Goal requireOwned(Long userId, Long goalId)` | 校验 `goalId` 属于 `userId`；否则 `NotFoundException`。只读事务。 |
| `Goal create(AppUser owner, String title, String description, GrowthDomain domain, GoalPriority priority, LocalDate deadline)` | 若该用户在 `PIPELINE_STATUSES` 内的目标数 **≥ 5**，抛 `ConflictException`；否则新建目标，默认状态 **`DECOMPOSING`**，`priority` 默认 `MEDIUM`。写事务。 |
| `Goal updateMeta(Long userId, Long goalId, ...)` | 在归属校验通过后，对非 `null` 的标题、描述、领域、优先级、截止日期做部分更新。写事务。 |
| `Goal updateStatus(Long userId, Long goalId, GoalStatus status)` | 归属校验后更新目标状态。写事务。 |
| `Goal updateMilestonesJsonSnapshot(Long userId, Long goalId, String milestonesJson)` | 归属校验后更新 `milestones_json` 快照字段（如 AI 拆解结果缓存）。写事务。 |

---

### 5.6 `MilestoneService`

依赖：`MilestoneRepository`、`GoalService`。

| 函数 | 作用 |
|:---|:---|
| `List<Milestone> listForGoal(Long userId, Long goalId)` | 先通过 `GoalService.requireOwned` 确认目标归属，再按 `sort_order` 升序返回里程碑列表。只读事务。 |
| `Milestone create(Long userId, Long goalId, String name, int sortOrder, BigDecimal estimatedWeeks, String deliverable, String dependenciesJson)` | 归属校验后新建里程碑，初始状态 **`PENDING`**，持久化。写事务。 |
| `Milestone updateStatus(Long userId, Long milestoneId, MilestoneStatus status, LocalDateTime completedAt)` | 按里程碑 id 加载；若里程碑所属目标的 `user_id` 不是当前用户，抛 `NotFoundException`（与「无权限」统一口径）。更新状态；若传入 `completed_at` 则用传入值，否则当状态为 **`COMPLETED`** 时自动填**当前时间**。写事务。 |

---

### 5.7 `PlanService`

依赖：`PlanRepository`、`GoalService`。

| 函数 | 作用 |
|:---|:---|
| `Optional<Plan> findActiveForGoal(Long userId, Long goalId)` | 先 `requireOwned` 目标，再取该目标下 **`ACTIVE` 且 version 最大** 的一条计划（若无则 `Optional.empty()`）。只读事务。 |
| `Plan createNextPlanVersion(Long userId, Long goalId, String aiPromptVersion)` | 归属校验后：将该目标下所有 **`ACTIVE`** 计划改为 **`DEPRECATED`**；新计划 `version = 当前全局最大 version + 1`（跨状态取最大版本号）、状态 **`ACTIVE`**，写入 `generated_at` 与 `ai_prompt_version`。与文档中「版本递增、旧版 deprecated」一致。写事务。 |

---

### 5.8 `TaskService`

依赖：`TaskRepository`。

| 函数 | 作用 |
|:---|:---|
| `List<Task> listForUserOnDate(Long userId, LocalDate scheduledDate)` | 某日该用户的全部任务，按创建时间升序。只读事务。 |
| `Task complete(Long userId, Long taskId, Integer actualMinutes, Integer qualityScore)` | 按任务 id + 用户加载；非 `PENDING` 抛 `ConflictException`；否则置 **`COMPLETED`**、`completed_at=now`，可选写入实际分钟与质量分（1–5，否则 `IllegalArgumentException`）。写事务。 |
| `Task skip(Long userId, Long taskId, String skipReason)` | `skipReason` 不能为空字符串；加载任务后须为 `PENDING`；否则分别 `IllegalArgumentException` / `ConflictException`；置 **`SKIPPED`** 并写入跳过原因。写事务。 |
| `Task scheduleTask(Long userId, Plan plan, Goal goal, Milestone milestone, String title, String description, LocalDate scheduledDate, int estimatedMinutes)` | **排期写入任务**：校验 `goal` 属于 `userId`、`plan` 属于该 `goal`；新建 `Task`，关联 `plan`/`goal`/用户，可选 `milestone`，写入标题、描述、计划日、预估分钟，状态 **`PENDING`**。调用方需保证计划与业务语义（如仅对 ACTIVE 计划排期）。写事务。 |

---

### 5.9 `AiInvokeLogService`

依赖：`AiInvokeLogRepository`。

| 函数 | 作用 |
|:---|:---|
| `AiInvokeLog save(AiInvokeLog log)` | 将已构造好的 `AiInvokeLog` 实体持久化（供 AI 调用前后写入审计）。写事务。 |

---

### 5.10 `AiPromptService`

依赖：`AiPromptRepository`。

| 函数 | 作用 |
|:---|:---|
| `Optional<AiPrompt> findActivePrompt(PromptType type)` | 按类型取当前 **`is_active`** 的 Prompt（同类型多条时取 `updated_at` 最新）。只读事务。 |

---

### 5.11 `AiFeedbackService`

依赖：`AiFeedbackRepository`。

| 函数 | 作用 |
|:---|:---|
| `List<AiFeedback> listForUser(Long userId)` | 用户全部 AI 反馈，按生成时间降序。只读事务。 |
| `long countUnread(Long userId)` | 未读反馈数量。只读事务。 |
| `AiFeedback append(AppUser user, Goal goal, AiFeedbackType feedbackType, String content)` | 新建反馈记录，`goal` 可为实体允许的空（若业务需要跨目标反馈可传 `null`），`read_by_user` 初始为 **`false`**。写事务。 |
| `void markRead(Long userId, Long feedbackId)` | 校验反馈属于该用户后，将 `read_by_user` 置 **`true`**。写事务。 |

---

### 5.12 `AdminUserService`

依赖：`AdminUserRepository`。

| 函数 | 作用 |
|:---|:---|
| `Optional<AdminUser> findByUsername(String username)` | 按登录名查询后台管理员（用于后续登录鉴权组装，不做密码校验）。只读事务。 |

---

## 6. 类之间的典型调用关系（简图）

```
AppUserService.registerNewUser
    → UidGenerator.nextUid
    → UserNotificationSettingsService.getOrCreate

GoalService.create / requireOwned
    ← MilestoneService / PlanService（归属校验）

PlanService.createNextPlanVersion
    → GoalService.requireOwned
    → PlanRepository（deprecate ACTIVE + 新版本）

TaskService.complete | skip
    → TaskRepository.findByIdAndUser_Id
```

---

## 7. 后续建议（非本次代码范围）

- 在接入 **`spring-boot-starter-web`** 后，用 `@ControllerAdvice` 将 `NotFoundException` → 404、`ConflictException` → 409 等统一映射。
- 密码哈希、令牌哈希、字段加密等仍应在应用层按安全规范实现，本文档所述 Service **不负责**加解密，仅维护表结构与状态流转。

---

## 8. AI 对话与助手任务（补充，2026-05-16）

完整业务流程见 **`md文档/AI对话与助手任务-流程说明.md`**；HTTP 见 **`md文档/HTTP接口-AI对话与通知.md`**。

| 类 | 职责摘要 |
|:---|:---|
| `AiChatService` | 对话主编排：规划 → 上下文 → 多轮工具 → 落库 → 推送 |
| `AiChatPlanningService` | 数据规划 JSON、意图分析（`multi-phase-enabled`） |
| `AiChatUserContextBuilder` | 组装 system 提示（画像 + 任务摘要） |
| `AiChatToolExecutor` | 执行 `list_tasks` / `create_task` 等工具 |
| `UserAssistantTaskService` | 助手任务 CRUD 与对话用 JSON 摘要 |
| `AiChatReminderSessionService` | 定时提醒/摘要写入**最新用户会话**（无则「任务提醒」兜底） |
| `AssistantTaskReminderService` | 到期扫描、站内通知与提醒正文 |
| `AssistantTaskReminderScheduler` | 定时触发提醒 |
| `InAppNotificationService` | 通知 CRUD、WebSocket 载荷组装 |
| `ChatRealtimePushService` | 按 userId 维护 WebSocket 连接并推送 |

---

*文档版本：与仓库中 Service / Repository 扩展代码同步整理。*
