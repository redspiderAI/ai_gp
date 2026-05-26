# HTTP 接口说明 — 认证与用户资料（v1）

本文档描述当前后端已实现的 **REST 接口**，与代码路径一致，便于本地查阅与前后端对齐。  
（不依赖 Swagger 在线页面；若需在线调试，仍可自行访问 `http://localhost:8000/swagger-ui.html`。）

**头像上传**：见下文 **§3.2.1**（`POST /api/v1/users/me/avatar`）与 **§3.2.2**（公开读取）；OpenAPI 见 `docs/openapi/v1-users.yaml`。

---

## 1. 通用约定

### 1.1 服务地址

- 默认主机：`http://localhost:8000`（端口见 `application.yaml` 中 `server.port`，可用环境变量 `PORT` 覆盖）。

### 1.2 URL 前缀

- 所有业务接口前缀：`/api/v1`

### 1.3 请求头

| 头名称 | 说明 |
|--------|------|
| `Content-Type` | 含请求体时使用 `application/json` |
| `Authorization` | 需鉴权接口：`Bearer {accessToken}`（`Bearer` 与令牌之间有一个空格） |

### 1.4 公开接口（无需 `Authorization`）

以下路径由过滤器放行，不校验 JWT：

- `POST /api/v1/auth/login`
- `POST /api/v1/auth/register`
- `POST /api/v1/auth/register/send-code`
- `POST /api/v1/auth/password/reset`
- `POST /api/v1/auth/password/reset/send-code`
- `POST /api/v1/auth/refresh`
- `GET /api/v1/public/avatars/{uid}` — 读取用户头像图片（**无需** Bearer，供 `<img src>` 使用）

其余以 `/api/` 开头的接口均需携带有效访问令牌（过滤器未拦截的非 `/api/` 路径如 Swagger 文档等除外）。

### 1.5 访问令牌（JWT）载荷说明

签发时包含（供前端理解，一般无需解析）：`sub`（内部用户 id）、`uid`（对外用户标识）、`sid`（会话 id，用于登出/改密保留当前设备）、`jti`、`exp` 等。

### 1.6 统一错误响应

业务异常与校验失败时，响应体多为 JSON：

```json
{
  "code": "NOT_FOUND",
  "message": "具体说明"
}
```

常见 `code`：`NOT_FOUND`、`CONFLICT`、`UNAUTHORIZED`、`VALIDATION_ERROR`、`BAD_REQUEST`、`INTERNAL_ERROR`。HTTP 状态码与语义对应（如 400、401、409 等）。

过滤器直接返回的 401 JSON 字段名相同（`code` / `message`）。

### 1.7 注册与登录：逐步要传的参数与返回值

以下均指 **请求体 JSON**（`Content-Type: application/json`），**无需** `Authorization`，除非另有说明。

#### 会话与换机说明（无需客户端 `deviceId`）

- 登录、注册成功后，服务端在 `user_sessions` 使用 **内置设备键** `builtin-{userId}` 与刷新令牌绑定；**客户端不必、也不应再传 `deviceId`**。
- 换手机后只要 **账号 + 密码** 重新登录即可拿到新令牌；**刷新**只需携带当前 `refreshToken`。

---

#### A. 用户注册（两步）

| 步骤 | 接口 | 发送（请求体） | 成功时得到（响应体） |
|------|------|----------------|----------------------|
| **A1 发验证码** | `POST /api/v1/auth/register/send-code` | `{ "email": "<未注册邮箱>" }` | `{ "expiresInSeconds": <秒>, "debugCode": "<仅开发配置开启时有>" }` |
| **A2 提交注册** | `POST /api/v1/auth/register` | `{ "email": "<与A1相同>", "password": "<至少8位>", "nickname": "<必填，最长50>", "verificationCode": "<6位邮箱验证码>" }` | 与登录相同结构的 **令牌包**（见下表「令牌包」） |

- **A1 说明**：仅支持 **邮箱**；`email` 须为 **未注册**；同一邮箱 **60 秒内** 只能发一次码。配置 `spring.mail` 时验证码走 **邮件**（见 `md文档/QQ邮箱SMTP与验证码.md`），否则为进程内内存。生产环境一般 **没有** `debugCode`；本地可把 `app.auth.verification-debug-return-code` 设为 `true` 便于联调。
- **A2 说明**：须与 A1 使用同一邮箱；**昵称必填**。**注册仅支持邮箱**；绑定手机号请在登录后通过 **PATCH `/api/v1/users/me`**（见第 3.2 节），绑定后可用 **手机号 + 同一登录密码** 登录。成功后 **自动登录**，直接拿到令牌。

---

#### B. 用户登录（一步）

| 步骤 | 接口 | 发送（请求体） | 成功时得到（响应体） |
|------|------|----------------|----------------------|
| **B1 登录** | `POST /api/v1/auth/login` | `{ "account": "<邮箱或11位手机号或16位uid>", "password": "<密码>" }` | **令牌包**（见下表） |

- **`account` 规则**：含 `@` 按邮箱；长度为 16 且以 `U`/`u` 开头按对外 `uid`；否则按 **11 位大陆手机号** 查 `user_identities`（须已在资料中绑定且与邮箱共用密码）。

---

#### 令牌包（`TokenResponse`，注册成功与登录成功结构相同）

| 字段 | 类型 | 含义与后续用法 |
|------|------|----------------|
| `accessToken` | string | **访问令牌（JWT）**。之后调受保护接口时放在请求头：`Authorization: Bearer <accessToken>` |
| `refreshToken` | string | **明文刷新令牌**，仅客户端保存；**不要**当作用户 id。过期前用下面「续期」换新的 access + refresh |
| `tokenType` | string | 固定为 `Bearer` |
| `expiresIn` | number | `accessToken` 有效秒数（由 `app.jwt.access-token-expire-minutes` 换算） |
| `uid` | string | 对外用户 id（`users.uid`），用于展示或业务关联 |

**续期（可选）**：`POST /api/v1/auth/refresh`，请求体：`{ "refreshToken": "<当前明文refresh>" }`，响应体仍是上表令牌包（滚动换新 refresh）。

---

## 2. 认证相关 `/api/v1/auth`

### 2.1 登录

- **方法 / 路径**：`POST /api/v1/auth/login`
- **鉴权**：不需要
- **说明**：校验密码后为该用户在库中 **创建或更新** 内置会话键（`builtin-{userId}`）对应的会话行，返回访问令牌与明文刷新令牌（库中仅存刷新令牌哈希）。  
  **`account` 三种形态**（按以下顺序识别）：
  1. 含 `@` → 按 **邮箱** 查 `user_identities`（`identity_type=email`，identifier 会按小写匹配）。
  2. 长度为 **16** 且首字符为 **`U` 或 `u`** → 视为对外 **`users.uid`**（会先转成大写再查表），再取该用户下已设置密码的邮箱或手机身份做密码校验。
  3. 其它 → 按 **11 位大陆手机号** 查 `user_identities`（`identity_type=phone`，须已绑定且已同步登录密码；与邮箱密码相同）。
- **请求体**（JSON）：

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| account | string | 是 | 邮箱、手机号，或 16 位对外 uid（`U` 开头） |
| password | string | 是 | 密码 |

- **200 响应体**：与 **2.6 刷新访问令牌** 中 `TokenResponse` 结构相同（`accessToken`、`refreshToken`、`tokenType`、`expiresIn`、`uid`）。
- **401**：账号或密码错误、账号已禁用/注销等（文案可能统一为「账号或密码错误」以降低枚举风险）。

**示例**：测试数据用户可用 `account = "UTEST00000000001"` 或 `account = "test01@example.com"`，密码见 `scripts/seed-5-test-users.sql` 说明。

---

### 2.2 注册：发送验证码

- **方法 / 路径**：`POST /api/v1/auth/register/send-code`
- **鉴权**：不需要
- **说明**：向 **未注册邮箱** 发注册验证码（仅邮箱；配置 SMTP 时走邮件，否则内存 + 可选 `debugCode`）。同一邮箱 **60 秒内** 不可重复发送。SMTP 配置见 `md文档/QQ邮箱SMTP与验证码.md`。
- **请求体**（JSON）：

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| email | string | 是 | 有效邮箱，与后续注册请求一致（服务端存小写） |

- **200 响应体**（JSON）：

| 字段 | 类型 | 说明 |
|------|------|------|
| expiresInSeconds | number | 验证码有效秒数（与 `app.auth.verification-ttl-seconds` 一致） |
| debugCode | string \| null | 仅当 `app.auth.verification-debug-return-code=true` 时返回明文验证码，便于本地联调；**生产必须为 false** |

- **409**：该邮箱已注册。
- **503**：已配置 SMTP 但邮件发送失败（如授权码错误、网络问题）；错误码 `SERVICE_UNAVAILABLE`，本次发码已回滚，可稍后重试。

---

### 2.3 注册

- **方法 / 路径**：`POST /api/v1/auth/register`
- **鉴权**：不需要
- **说明**：校验 **邮箱** 验证码（一次性）后创建 `users`、绑定 **邮箱+密码** 身份、写入默认通知设置，并 **自动登录** 返回令牌对。邮箱与发码阶段须一致（小写存库）。**不支持手机号注册**；手机号请在登录后 PATCH 资料绑定（见 3.2）。
- **请求体**（JSON）：

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| email | string | 是 | 与发码时相同的邮箱 |
| password | string | 是 | 登录密码，至少 8 位 |
| nickname | string | 是 | 昵称，最长 50 |
| verificationCode | string | 是 | 邮箱收到的 6 位数字验证码 |

- **200 响应体**：同 **2.6** `TokenResponse`。
- **401**：验证码错误、过期等。
- **409**：该邮箱已注册。

---

### 2.4 找回密码：发送验证码

- **方法 / 路径**：`POST /api/v1/auth/password/reset/send-code`
- **鉴权**：不需要
- **说明**：向 **已注册且已设置密码** 的邮箱或手机号发码；频控同 2.2。邮箱在已配置 SMTP 时发邮件，否则内存验证码。
- **请求体**：同 2.2（`{ "account": "..." }`）。
- **200 响应体**：同 2.2（`expiresInSeconds`、`debugCode`）。
- **400**：账号不存在、未设置密码登录等。
- **503**：同 2.2（SMTP 发送失败时）。

---

### 2.5 找回密码：重置密码

- **方法 / 路径**：`POST /api/v1/auth/password/reset`
- **鉴权**：不需要
- **说明**：校验验证码后更新密码哈希，并 **撤销该用户全部会话**（所有设备需重新登录）。
- **请求体**（JSON）：

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| account | string | 是 | 邮箱或手机号 |
| verificationCode | string | 是 | 6 位验证码 |
| newPassword | string | 是 | 新密码，至少 8 位 |

- **204**：成功，无响应体。
- **401**：验证码错误、过期等。

---

### 2.6 刷新访问令牌

- **方法 / 路径**：`POST /api/v1/auth/refresh`
- **鉴权**：不需要
- **说明**：使用明文 `refreshToken` 换新令牌；服务端在库中按 **SHA-256 哈希** 存储刷新令牌，滚动更新。无需也不校验客户端设备指纹。
- **请求体**（JSON）：

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| refreshToken | string | 是 | 登录或上次刷新得到的明文刷新令牌 |

- **200 响应体**（JSON）：

| 字段 | 类型 | 说明 |
|------|------|------|
| accessToken | string | 新 JWT |
| refreshToken | string | 新明文刷新令牌（请安全保存） |
| tokenType | string | 固定 `Bearer` |
| expiresIn | number | 访问令牌有效秒数 |
| uid | string | 对外用户标识 |

- **401**：刷新令牌无效或已过期等。

---

### 2.7 修改密码

- **方法 / 路径**：`POST /api/v1/auth/change-password`
- **鉴权**：需要 `Authorization: Bearer {accessToken}`
- **说明**：校验原密码后更新；成功后 **保留当前 JWT 对应会话**，**撤销该用户其余设备会话**。身份优先使用带密码的 `email`，否则 `phone`；若无可用密码身份则 **400**。
- **请求体**（JSON）：

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| oldPassword | string | 是 | 当前密码 |
| newPassword | string | 是 | 新密码，至少 8 位 |

- **200**：无响应体。
- **401**：原密码错误等。

---

### 2.8 登出

- **方法 / 路径**：`POST /api/v1/auth/logout`
- **鉴权**：需要 Bearer
- **说明**：默认只撤销当前 JWT 对应会话（依赖载荷中的会话 id）；`allDevices=true` 时撤销该用户 **全部** 会话。若令牌中无会话 id，为安全起见会撤销全部会话。
- **请求体**（JSON，可选）：

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| allDevices | boolean | 否 | 默认 `false`；`true` 表示全部设备下线 |

- **200**：无响应体。

---

## 3. 当前用户资料 `/api/v1/users`

以下接口均需 Bearer；且用户 `status` 须为 **1（正常）**（注销/禁用会返回未授权类错误）。

| 接口 | 方法 | 说明 |
|------|------|------|
| 获取资料 | `GET /api/v1/users/me` | 含 `avatarUrl` |
| 更新资料（JSON） | `PATCH /api/v1/users/me` | 可改昵称、外链头像等 |
| **上传头像** | **`POST /api/v1/users/me/avatar`** | **multipart，自动写 `avatarUrl`** |
| 读取头像（公开） | `GET /api/v1/public/avatars/{uid}` | **无需登录**，见 3.2.2 |
| 首次画像 | `PATCH /api/v1/users/me/onboarding` | 年龄、职业、爱好、昵称（未填时）、探索方向 |
| 注销 | `DELETE /api/v1/users/me` | 软删除 |

OpenAPI 片段：`docs/openapi/v1-users.yaml`（与 SpringDoc `/swagger-ui.html` 互补）。

### 3.1 获取当前用户资料

- **方法 / 路径**：`GET /api/v1/users/me`
- **200 响应体**（JSON）：

| 字段 | 类型 | 说明 |
|------|------|------|
| uid | string | 对外 uid |
| nickname | string \| null | 昵称 |
| avatarUrl | string \| null | 头像 URL；本地上传后为 `{APP_PUBLIC_BASE_URL}/api/v1/public/avatars/{uid}` |
| weeklyHours | number \| null | 每周可投入小时数 0–40 |
| timezone | string | 时区 |
| language | string | 语言偏好 |
| status | number | 1 正常 / 2 禁用 / 3 注销 |
| createdAt | string | 注册时间（ISO 风格日期时间，见全局 Jackson 配置） |
| updatedAt | string | 最近更新时间 |
| phone | string \| null | 已绑定手机号（脱敏，如 `138****8000`）；未绑定为 `null` |
| age | number \| null | 年龄（周岁） |
| occupation | string \| null | 职业 |
| hobbies | string \| null | 爱好 |
| explorationInterests | string \| null | 希望探索的专业方向等 |
| onboardingCompleted | boolean | 是否已完成首次画像填写 |

---

### 3.2 更新当前用户资料

- **方法 / 路径**：`PATCH /api/v1/users/me`
- **请求体**（JSON）：字段均可选，未传的字段不改。

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| nickname | string | 最长 50 | 昵称 |
| avatarUrl | string | 最长 500 | 头像 URL；推荐用 **3.2.1 上传头像** 自动写入，也可继续填外链 |
| weeklyHours | number | 0–40 | 每周可投入小时数 |
| phone | string | 11 位大陆号 `1[3-9]…` | 绑定到当前用户；**与邮箱登录密码共用**（绑定后可用手机号+密码登录）；暂 **无短信验证**；与他人冲突时 **409** |

- **200**：同 3.1 响应结构。

---

### 3.2.1 上传头像并自动更新资料

- **方法 / 路径**：`POST /api/v1/users/me/avatar`
- **鉴权**：`Authorization: Bearer {accessToken}`
- **Content-Type**：`multipart/form-data`（由客户端自动生成 boundary；**不要**手动设成 `application/json`）
- **表单字段**（`multipart` 的 part 名必须为 `file`）：

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| file | file | 是 | JPEG / PNG / WebP / GIF；`Content-Type` 须为 `image/jpeg`、`image/png`、`image/webp`、`image/gif` 之一；单张 ≤ `app.max-image-upload-bytes`（默认 **5MB**，见 `APP_MAX_IMAGE_UPLOAD_BYTES`） |

- **说明**：
  - 服务端保存到 `{app.upload-path}/avatars/{userId}/avatar.{ext}`（默认 `uploads/avatars/...`）。
  - 成功后将 `users.avatar_url` 更新为公开地址：**`{APP_PUBLIC_BASE_URL}/api/v1/public/avatars/{uid}`**（默认如 `http://localhost:8000/api/v1/public/avatars/U0123456789ABCDE`）。
  - 该 URL **无需 Bearer**，前端可直接：`<img src="{avatarUrl}" />`。
  - 再次上传会**覆盖**同用户旧头像文件（扩展名可能变化）。
  - **不要**用 `PATCH /me` 的 `avatarUrl` 传本地文件路径；本地上传请只用本接口。

- **请求头**：

| 头 | 值 |
|----|-----|
| Authorization | `Bearer {accessToken}` |
| Content-Type | 由 `FormData` 自动设置（含 `multipart/form-data; boundary=...`） |

- **200 响应体**：与 3.1 相同 JSON（`UserProfileResponse`），`avatarUrl` 已为公开地址。

**示例响应**：

```json
{
  "uid": "U0123456789ABCDE",
  "nickname": "小明",
  "avatarUrl": "http://localhost:8000/api/v1/public/avatars/U0123456789ABCDE",
  "weeklyHours": 10,
  "timezone": "Asia/Shanghai",
  "language": "zh-CN",
  "status": 1,
  "createdAt": "2026-05-01 10:00:00",
  "updatedAt": "2026-05-17 14:30:00",
  "phone": null,
  "age": null,
  "occupation": null,
  "hobbies": null,
  "explorationInterests": null,
  "onboardingCompleted": false
}
```

- **错误**：

| HTTP | code | 典型 message | 场景 |
|------|------|--------------|------|
| 400 | `BAD_REQUEST` | 请选择图片文件 | 未选文件或空文件 |
| 400 | `BAD_REQUEST` | 仅支持 JPEG、PNG、WebP、GIF 图片 | MIME 不在白名单 |
| 400 | `BAD_REQUEST` | 图片过大，单张不超过 5MB | 超过大小上限 |
| 401 | `UNAUTHORIZED` | — | 未登录或 Token 无效 |
| 401 | `UNAUTHORIZED` | — | 用户已注销/禁用（`requireActive`） |

- **curl 示例**（将 `TOKEN`、图片路径替换为实际值）：

```bash
curl -X POST "http://localhost:8000/api/v1/users/me/avatar" \
  -H "Authorization: Bearer TOKEN" \
  -F "file=@/path/to/photo.jpg;type=image/jpeg"
```

- **前端 fetch 示例**（浏览器；不要自己设 `Content-Type`）：

```javascript
const form = new FormData();
form.append("file", fileInput.files[0]); // 字段名必须是 file

const res = await fetch("http://localhost:8000/api/v1/users/me/avatar", {
  method: "POST",
  headers: { Authorization: `Bearer ${accessToken}` },
  body: form,
});
const profile = await res.json();
// profile.avatarUrl 可直接用于 <img src={profile.avatarUrl} />
```

- **Swagger UI**：在「用户资料」下找到 `POST /api/v1/users/me/avatar`，Authorize 后选择 `file` 上传；勿填写 Query 里的 `user` 占位参数。

---

### 3.2.2 公开读取用户头像

- **方法 / 路径**：`GET /api/v1/public/avatars/{uid}`
- **鉴权**：**无需** Bearer（已在 1.4 公开路径放行）
- **路径参数**：

| 参数 | 类型 | 说明 |
|------|------|------|
| uid | string | 16 位对外用户 id，格式 `U` + 15 位十六进制，如 `U0123456789ABCDE`（与登录返回、`GET /me` 的 `uid` 一致） |

- **200**：图片**二进制**响应体；`Content-Type` 为 `image/jpeg`、`image/png`、`image/webp` 或 `image/gif`（与上传时格式一致）。
- **404**：`{"code":"NOT_FOUND","message":"头像不存在"}` — 用户不存在、账号非正常、或尚未上传过头像。

**前端用法**：

```html
<img src="http://localhost:8000/api/v1/public/avatars/U0123456789ABCDE" alt="头像" />
```

上传成功后 `GET /me` 返回的 `avatarUrl` 即为此 URL（主机名随 `APP_PUBLIC_BASE_URL` 变化）。

---

### 3.3 更新首次登录用户画像（年龄、职业、爱好、昵称、探索方向）

- **方法 / 路径**：`PATCH /api/v1/users/me/onboarding`
- **说明**：用于注册后首次进入产品时的问卷/画像；**部分更新**：请求体里未出现的字段保持原值。传 **空字符串** `""` 可清空对应文本字段（`age` 除外）；`onboardingCompleted` 未传则不改。
- **请求体**（JSON）：

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| age | number | 1–120 | 年龄（周岁） |
| occupation | string | 最长 200 | 职业，如学生、产品经理、自由职业 |
| hobbies | string | 最长 4000 | 爱好 |
| nickname | string | 最长 50 | 昵称；**仅当账号尚未设置昵称时**才会写入，已有昵称时忽略 |
| explorationInterests | string | 最长 4000 | 希望探索的专业方向、领域等 |
| onboardingCompleted | boolean | — | 是否标记为已完成首次画像；`true` / `false` |

- **200**：同 3.1 响应结构。
- **400**：字段超长、年龄超出范围等校验错误。

**示例请求**：

```json
{
  "age": 22,
  "occupation": "大学生",
  "hobbies": "阅读、跑步、摄影",
  "nickname": "小明",
  "explorationInterests": "人工智能、产品设计",
  "onboardingCompleted": true
}
```

---

### 3.4 注销当前账号（软删除）

- **方法 / 路径**：`DELETE /api/v1/users/me`
- **说明**：将用户状态置为 **3（注销）**，并撤销 **全部** 会话。
- **204**：成功，无响应体。

---

### 3.5 用户 LLM 配置（平台代调 / 自带 API Key）

**默认行为**：未保存过设置的用户、或 `billingMode=PLATFORM`、或选了 BYOK 但未保存 `apiKey` 时，**一律使用官方平台 Key** 调用对话。  
**仅当**用户显式 `billingMode=BYOK` **且**已保存有效 `apiKey` 后，对话才改用自有 Token。

配置保存后，调用 **`POST /api/v1/ai/chat`** 时按上述规则选择密钥；对话接口本身不变。

**数据库**：`scripts/mysql-user-llm-settings.sql`（或 `python scripts/run-mysql-migrations.py`）。

#### 3.5.1 获取当前配置

- **方法 / 路径**：`GET /api/v1/users/me/llm/settings`
- **200**：

| 字段 | 类型 | 说明 |
|------|------|------|
| billingMode | string | 用户保存的偏好：`PLATFORM` / `BYOK`（未配 Key 的 BYOK 对话仍走官方） |
| provider | string | 提供商键名，如 `mimo`、`ollama` |
| baseUrl | string | 展示用 baseUrl |
| model | string | 展示用 model |
| apiKeyConfigured | boolean | 是否已保存用户 Key |
| apiKeyHint | string \| null | 脱敏尾号，如 `****abcd` |
| usingPlatformKey | boolean | **当前对话实际**是否使用官方 Key |
| usingOwnApiKey | boolean | **当前对话实际**是否使用用户自有 Key |
| neverConfigured | boolean | `true` 表示从未保存过设置（系统默认官方） |

#### 3.5.2 保存配置

- **方法 / 路径**：`PUT /api/v1/users/me/llm/settings`
- **请求体**（JSON）：

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| billingMode | string | 否 | `PLATFORM`（**默认**）或 `BYOK`；不传等同 `PLATFORM` |
| provider | string | 是 | 须在平台已配置的提供商列表内 |
| apiKey | string | 条件 | BYOK 首次保存必填；`null` 表示不修改；`""` 清除 |
| baseUrl | string | 否 | 覆盖默认 baseUrl；`null` 不修改；`""` 清除覆盖 |
| model | string | 否 | 覆盖默认 model；`null` 不修改；`""` 清除覆盖 |

- **200**：同 3.5.1。
- **400**：参数非法、BYOK 未提供 Key、**或保存前连通性探测失败**（默认开启）。`message` 为中文可操作提示，例如：
  - `不支持的 provider，可选: mimo, ollama。自带 Key（BYOK）时 provider 仍须填上述键名之一，第三方服务（如 DeepSeek）请通过 apiKey、baseUrl、model 指定`
  - `API Key 无效、无权限或已过期，请核对后重试`
  - `模型接口地址不存在，请确认 baseUrl 为 OpenAI 兼容根路径且一般以 /v1 结尾`
  - `模型名称不可用或不存在，请核对 model（当前：xxx）`
- **503**：`PLATFORM` 且平台侧未配置对应提供商 Key。
- **说明**：保存成功前会对即将生效的配置调用一次最小 `chat/completions`（`max_tokens=1`）；探测失败**不会**写入数据库。用户 Key 以 AES-GCM 加密存入 `user_llm_settings.api_key_cipher`，响应 **永不** 返回完整 Key。

**BYOK + DeepSeek 示例**（`provider` 不能写 `deepseek`，须用 `mimo` 或 `ollama` 槽位）：

```json
{
  "billingMode": "BYOK",
  "provider": "mimo",
  "apiKey": "sk-xxx",
  "baseUrl": "https://api.deepseek.com/v1",
  "model": "deepseek-chat"
}
```

#### 3.5.3 恢复平台代调

- **方法 / 路径**：`DELETE /api/v1/users/me/llm/settings`
- **说明**：`billingMode` 置为 `PLATFORM` 并清除已存用户 Key。
- **200**：同 3.5.1。

#### 3.5.4 列出可选提供商

- **方法 / 路径**：`GET /api/v1/users/me/llm/providers`
- **200**：

| 字段 | 类型 | 说明 |
|------|------|------|
| defaultProvider | string | 应用默认提供商 |
| providers | array | 项含 `key`、`model`、`baseUrl`、`platformAvailable`（平台侧是否可用，不含密钥） |

---

## 4. 配置与安全（部署备忘）

**本地与生产如何分别配置 JWT、数据库等**：见 **`md文档/环境与密钥配置.md`**。

| 配置项 | 说明 |
|--------|------|
| `JWT_SECRET_KEY` / `app.jwt.secret-key` | HS256 密钥，**至少 32 字节**；注册/登录签发令牌必填；生产仅环境变量、与本地密钥必须不同。 |
| `app.jwt.access-token-expire-minutes` | 访问令牌有效分钟数。 |
| `app.jwt.refresh-token-expire-days` | 刷新令牌对应会话过期天数。 |
| `app.auth.verification-ttl-seconds` | 注册/找回密码验证码有效秒数（默认 300）。 |
| `app.auth.verification-debug-return-code` | 为 `true` 时发码接口 JSON 会带 `debugCode`；**仅开发联调**，生产必须为 `false`。 |

---

## 5. 与数据脚本的关系

批量测试用户可参考仓库内 SQL：`scripts/seed-5-test-users.sql`（与库表结构对应，不在本文展开）。

---

## 6. 相关文档

| 文档 | 说明 |
|------|------|
| `md文档/环境与密钥配置.md` | JWT、.env、dev/prod 配置分层 |
| `md文档/HTTP接口-AI对话与通知.md` | AI 对话、助手任务、站内通知、WebSocket |
| `md文档/HTTP接口-语音转写.md` | 上传语音 → 文本 → 再发 AI 对话 |
| `md文档/本地语音转写-STT部署.md` | 内网 Whisper STT Docker 部署 |
| `md文档/AI对话与助手任务-流程说明.md` | 对话与提醒业务流程（非接口字段） |

---

## 7. 文档维护

接口变更时，请同步更新 **本文件**（`md文档/HTTP接口-认证与用户.md`），保持与控制器代码一致。
