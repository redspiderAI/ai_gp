# HTTP 接口说明 — 语音转写（v1）

前端上传一段录音，后端转发至**内网 STT 服务**（默认 `http://127.0.0.1:9000`），返回识别文本，再调用 **`POST /api/v1/ai/chat`** 发送对话。

通用鉴权、错误格式见 **`md文档/HTTP接口-认证与用户.md`** 第 1 章。  
**STT 如何在本机/内网启动**：见 **`md文档/本地语音转写-STT部署.md`**。

---

## 1. 语音转文字

- **方法 / 路径**：`POST /api/v1/speech/transcribe`
- **鉴权**：`Authorization: Bearer {accessToken}`
- **Content-Type**：`multipart/form-data`（勿手设 `application/json`）

### Swagger 浏览器录音（本地调试）

1. 打开 `http://localhost:8000/swagger-ui.html`（端口以 `PORT` 为准）
2. 右上角 **Authorize** 填入登录后的 `accessToken`
3. 展开 **语音转写** → `POST /api/v1/speech/transcribe`
4. 顶部会出现绿色边框的 **「浏览器录音测试」**：开始录音 → 停止 → **识别并上传**
5. 识别结果展示在面板内，可复制到 `POST /api/v1/ai/chat` 的 `message`  
   录音也会自动填入下方 Swagger 的 `file` 字段，仍可使用原生 **Execute** 调试。

### 1.1 请求

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| file | file | 是 | 浏览器录音多为 `audio/webm`；亦支持 wav、mp3、ogg、m4a 等 |

| 限制 | 默认值 | 配置 |
|------|--------|------|
| 单文件大小 | 10MB | `app.speech.max-audio-bytes` / `APP_SPEECH_MAX_BYTES` |

### 1.2 成功响应 200

```json
{
  "text": "帮我记一下明天下午三点开会"
}
```

将 `text` 填入：

```json
POST /api/v1/ai/chat
{ "message": "<上一步的 text>" }
```

### 1.3 错误

| HTTP | code | 典型场景 |
|------|------|----------|
| 400 | `BAD_REQUEST` | 未选文件、格式不支持、过大、未识别到内容、STT 返回 4xx |
| 401 | `UNAUTHORIZED` | 未登录 |
| 503 | `SPEECH_DISABLED` | `app.speech.enabled=false` |
| 503 | `SPEECH_UNAVAILABLE` | STT 未启动或无法连接 `app.speech.base-url` |

### 1.4 curl 示例

```bash
curl -X POST "http://localhost:8000/api/v1/speech/transcribe" \
  -H "Authorization: Bearer TOKEN" \
  -F "file=@recording.webm;type=audio/webm"
```

### 1.5 前端 fetch 示例

```javascript
const form = new FormData();
form.append("file", blob, "speech.webm");

const res = await fetch("http://localhost:8000/api/v1/speech/transcribe", {
  method: "POST",
  headers: { Authorization: `Bearer ${accessToken}` },
  body: form,
});
const { text } = await res.json();
// 再 POST /api/v1/ai/chat { message: text }
```

---

## 2. 配置（后端）

| 配置键 | 环境变量 | 说明 |
|--------|----------|------|
| `app.speech.enabled` | `APP_SPEECH_ENABLED` | 总开关 |
| `app.speech.base-url` | `APP_SPEECH_BASE_URL` | 内网 STT 根地址，默认 `http://127.0.0.1:9000` |
| `app.speech.transcribe-path` | `APP_SPEECH_TRANSCRIBE_PATH` | 默认 `/asr`（whisper-asr-webservice） |
| `app.speech.upstream-file-part-name` | `APP_SPEECH_UPSTREAM_FILE_PART` | 转发字段名，默认 `audio_file` |

完整列表见 **`md文档/环境与密钥配置.md`** §3.4。

---

## 3. 文档维护

接口或 STT 约定变更时，同步本文件、`docs/openapi/v1-speech.yaml`、`md文档/本地语音转写-STT部署.md`。
