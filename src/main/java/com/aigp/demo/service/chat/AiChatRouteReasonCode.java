package com.aigp.demo.service.chat;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * 路由规划 LLM 必须选择的 {@code routeReasonCode}（禁止自由发挥 reason 文案）。
 */
public enum AiChatRouteReasonCode {
	GREETING_OR_SMALLTALK("问候或短句闲聊"),
	GENERAL_QA("一般问答或闲聊"),
	ASSISTANT_TASK_LIST("查询助手待办"),
	ASSISTANT_TASK_STATUS_QUERY("查询任务是否完成"),
	ASSISTANT_TASK_CREATE("创建助手待办或提醒"),
	ASSISTANT_TASK_UPDATE("修改助手待办"),
	ASSISTANT_TASK_COMPLETE("标记助手待办完成"),
	ASSISTANT_TASK_DELETE("取消助手待办"),
	GROWTH_TASK_LIST("查询成长计划每日任务"),
	GROWTH_TASK_COMPLETE("标记成长计划任务完成"),
	PLAN_PROPOSAL("制定学习计划草案"),
	USER_PROFILE("查询用户画像"),
	UNSUPPORTED_FEATURE("用户请求未上线能力"),
	MIXED_OR_OTHER("混合意图或其他");

	private final String label;

	AiChatRouteReasonCode(String label) {
		this.label = label;
	}

	public String label() {
		return label;
	}

	public static Optional<AiChatRouteReasonCode> fromCode(String raw) {
		if (raw == null || raw.isBlank()) {
			return Optional.empty();
		}
		String key = raw.trim().toUpperCase(Locale.ROOT);
		return Arrays.stream(values()).filter(c -> c.name().equals(key)).findFirst();
	}

	/** 供规划 LLM system 附录：枚举名列表。 */
	public static String allowedCodesForPrompt() {
		StringBuilder sb = new StringBuilder();
		for (AiChatRouteReasonCode code : values()) {
			sb.append("- ").append(code.name()).append("：").append(code.label).append('\n');
		}
		return sb.toString();
	}
}
