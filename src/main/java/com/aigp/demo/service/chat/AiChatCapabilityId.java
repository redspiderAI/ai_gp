package com.aigp.demo.service.chat;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * 对话路由能力 ID（与规划 JSON 中 capabilities / unsupported 字段一致）。
 * 新增功能时在此注册，并在 {@link AiChatCapabilityCatalog} 中标记是否已上线。
 */
public enum AiChatCapabilityId {
	CHAT("chat", "普通对话与问答", true),
	CHAT_HISTORY("chat_history", "引用本会话历史（续聊、指代上文）", true),
	USER_PROFILE("user_profile", "用户画像（昵称、爱好等）", true),
	ASSISTANT_TASKS("assistant_tasks", "助手待办：记录、查询、修改、取消", true),
	/** AI 生成成长计划草案，待用户确认后入库（非直接写 tasks 表） */
	PLAN_PROPOSAL("plan_proposal", "成长计划草案：拟定方案供用户确认", true),
	GROWTH_PLAN_TASKS("growth_plan_tasks", "成长计划每日任务（goals/plans/tasks 表）", false),
	GOALS("goals", "长期目标与里程碑管理", false);

	private final String id;
	private final String label;
	private final boolean available;

	AiChatCapabilityId(String id, String label, boolean available) {
		this.id = id;
		this.label = label;
		this.available = available;
	}

	public String id() {
		return id;
	}

	public String label() {
		return label;
	}

	public boolean available() {
		return available;
	}

	public static Optional<AiChatCapabilityId> fromId(String raw) {
		if (raw == null || raw.isBlank()) {
			return Optional.empty();
		}
		String key = raw.trim().toLowerCase(Locale.ROOT);
		return Arrays.stream(values()).filter(c -> c.id.equals(key)).findFirst();
	}
}
