package com.aigp.demo.config;

import java.util.LinkedHashMap;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "app")
public class AppProperties {

	private final Jwt jwt = new Jwt();
	private final Auth auth = new Auth();
	private final Admin admin = new Admin();
	private final Llm llm = new Llm();
	private final Vlm vlm = new Vlm();
	private final Chat chat = new Chat();
	private final TaskReminder taskReminder = new TaskReminder();
	private final CompanionMemory companionMemory = new CompanionMemory();
	private final Speech speech = new Speech();
	private final MobilePush mobilePush = new MobilePush();
	private final GrowthTask growthTask = new GrowthTask();

	/** 本地上传根目录（相对路径基于进程工作目录） */
	private String uploadPath = "uploads";
	/** 对外访问根 URL，用于生成图片链接（如 http://localhost:8000） */
	private String publicBaseUrl = "http://localhost:8000";
	/** 单张图片最大字节数，默认 5MB */
	private long maxImageUploadBytes = 5L * 1024 * 1024;

	@Getter
	@Setter
	public static class Jwt {
		/**
		 * HS256 签名密钥（建议通过环境变量注入，长度至少 32 字节）。
		 */
		private String secretKey = "";
		private long accessTokenExpireMinutes = 1440;
		/**
		 * 刷新令牌在数据库中的有效天数（会话 {@code user_sessions.expires_at}）。
		 */
		private int refreshTokenExpireDays = 30;
	}

	/**
	 * 注册 / 找回密码等场景的验证码策略（当前为进程内内存实现，生产需接短信或邮件网关）。
	 */
	@Getter
	@Setter
	public static class Auth {
		/** 验证码有效时长（秒） */
		private int verificationTtlSeconds = 300;
		/**
		 * 为 true 时，发码接口响应体会带上明文验证码，仅用于本地联调；生产环境必须为 false。
		 */
		private boolean verificationDebugReturnCode = false;
	}

	@Getter
	@Setter
	public static class Admin {
		private final Bootstrap bootstrap = new Bootstrap();

		@Getter
		@Setter
		public static class Bootstrap {
			private String username = "admin";
			private String password = "";
		}
	}

	@Getter
	@Setter
	public static class Llm {
		private String apiKey = "";
		private String baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1";
		private String model = "qwen3.5-flash";
	}

	@Getter
	@Setter
	public static class Vlm {
		private String apiKey = "";
		private String baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1";
		private String model = "qwen-vl-max-latest";
	}

	/**
	 * AI 对话：OpenAI 兼容接口，支持多提供商（如小米 MiMo、本地 Ollama）。
	 */
	@Getter
	@Setter
	public static class Chat {
		/** 默认提供商键名：mimo / ollama */
		private String defaultProvider = "mimo";
		/** 带入模型的最近消息条数（不含 system） */
		private int maxHistoryMessages = 24;
		/** 单轮对话内工具调用最大轮次 */
		private int maxToolRounds = 5;
		/** 是否启用「先规划数据 → 意图分析 → 执行」多阶段（中间过程不落库） */
		private boolean multiPhaseEnabled = true;
		/** 闲聊/短句走确定性路由，跳过 plan（及无任务时的 intent）以降低延迟 */
		private boolean fastPathEnabled = true;
		/** 规划阶段带入的最近对话条数（仅 USER/ASSISTANT 文本摘要） */
		private int planningHistorySnippetMessages = 6;
		/** 对话回复完成后是否 WebSocket 推送 CHAT_REPLY（用户在线时） */
		private boolean pushOnReplyEnabled = true;
		/** 保存用户 LLM 设置（PUT settings）时是否探测 apiKey/baseUrl/model 可用 */
		private boolean validateLlmSettingsOnSave = true;
		/** 为 true 时输出 AI 对话中间过程调试记录（规划/模型/工具）；生产务必 false */
		private boolean pipelineDebugLogEnabled = false;
		/** 中间过程追加写入的 txt 路径（相对路径基于进程工作目录，如 logs/ai-chat-pipeline.txt） */
		private String pipelineDebugLogFile = "logs/ai-chat-pipeline.txt";
		private Map<String, ChatProvider> providers = new LinkedHashMap<>();
	}

	@Getter
	@Setter
	public static class ChatProvider {
		private String apiKey = "";
		private String baseUrl = "";
		private String model = "";
	}

	/** 助手任务（user_assistant_tasks）到期提醒：站内会话 + 通知 + WebSocket */
	@Getter
	@Setter
	public static class TaskReminder {
		private boolean enabled = true;
		/** 写入「任务提醒」会话并生成站内通知 */
		private boolean inAppEnabled = true;
		/** 站内通知创建后 WebSocket 推送给在线用户 */
		private boolean pushEnabled = true;
		/** 每分钟第 0 秒触发（按用户本地 due_at 到点提醒） */
		private String cron = "0/30 * * * * ?";
		private String zone = "Asia/Shanghai";
		/** 仅 due_date、无 due_at 时，在截止日当天该时刻提醒（HH:mm）；亦为每日任务摘要时刻 */
		private String defaultDueDateReminderTime = "08:00";
		/** 是否将提醒扫描/投递过程写入调试 txt（默认开启，生产可用环境变量关闭） */
		private boolean debugLogEnabled = true;
		/** 提醒调试日志路径（相对进程工作目录） */
		private String debugLogFile = "logs/task-reminder.txt";
	}

	/**
	 * 用户陪伴记忆：每周六凌晨总结、周六早上与任务提醒一并推送本周回顾。
	 */
	@Getter
	@Setter
	public static class CompanionMemory {
		private boolean enabled = true;
		/** 每周六 03:00 执行「本周总结 + 合并长期记忆」 */
		private String summarizeCron = "0 0 3 ? * SAT";
		private String zone = "Asia/Shanghai";
		/** 周六早上推送回顾的时刻（用户本地，默认与任务仅日期提醒同为 08:00） */
		private String digestDeliveryTime = "08:00";
		/** 写入「本周回顾」会话并生成站内通知 */
		private boolean digestInAppEnabled = true;
		/** 推送时是否 WebSocket 通知（与任务提醒 push 独立配置） */
		private boolean digestPushEnabled = true;
		/** 单用户单周纳入总结的对话消息条数上限 */
		private int maxMessagesPerWeek = 200;
		/** 长期记忆最大字符数（超出则截断） */
		private int maxMemoryChars = 12000;
	}

	/**
	 * 语音转写：Java 转发至内网 Whisper ASR HTTP（默认 whisper-asr-webservice 约定）。
	 */
	@Getter
	@Setter
	public static class Speech {
		private boolean enabled = true;
		/** 内网 STT 根地址，仅服务端访问，勿暴露公网 */
		private String baseUrl = "http://127.0.0.1:9000";
		/** 转写路径，whisper-asr-webservice 默认为 /asr */
		private String transcribePath = "/asr";
		/** 转发至 STT 时的 multipart 字段名（whisper-asr-webservice 为 audio_file） */
		private String upstreamFilePartName = "audio_file";
		private String queryLanguage = "zh";
		private String queryOutput = "json";
		private boolean queryEncode = true;
		private String queryTask = "transcribe";
		private long maxAudioBytes = 10L * 1024 * 1024;
		private int connectTimeoutMs = 5000;
		private int readTimeoutMs = 120000;
	}

	/**
	 * 系统级移动推送：默认 uni-push 2.0（云函数 URL，Firebase 在 DCloud 托管）；可选 provider=fcm 直连。
	 */
	@Getter
	@Setter
	public static class MobilePush {
		/** 总开关 */
		private boolean enabled = false;
		/** unipush（默认）或 fcm */
		private String provider = "unipush";
		/** uni-push 2.0：云函数 URL 化后的 HTTPS 地址（POST JSON） */
		private String unipushCloudUrl = "";
		/** uniCloud URL 化安全通讯密钥（与云函数校验一致，可空） */
		private String unipushHttpSecret = "";
		/** provider=fcm 时：Firebase 服务账号 JSON 路径 */
		private String credentialsPath = "";
		/** AI 对话回复是否发系统推送 */
		private boolean chatReplyEnabled = false;
	}

	/** 成长计划 {@code tasks} 表：开始执行、到时自动完成、跨日未完成。 */
	@Getter
	@Setter
	public static class GrowthTask {
		/** 是否启用每分钟状态扫描（与 task-reminder 同窗） */
		private boolean executionEnabled = true;
	}
}
