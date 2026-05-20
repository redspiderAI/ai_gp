package com.aigp.demo.service.chat;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.util.StringUtils;

/** 能力注册表：生成规划 prompt、校验 ID、生成「暂未开放」话术。 */
public final class AiChatCapabilityCatalog {

	private AiChatCapabilityCatalog() {}

	public static String plannerSystemAppendix() {
		StringBuilder sb = new StringBuilder();
		sb.append("【已上线能力】只能从下列 id 的 capabilities 中选择（可多选）：\n");
		for (AiChatCapabilityId cap : AiChatCapabilityId.values()) {
			if (cap.available()) {
				sb.append("- ")
						.append(cap.id())
						.append("：")
						.append(cap.label())
						.append('\n');
			}
		}
		sb.append("\n【未上线能力】用户若明确需要，写入 unsupported 数组（id 如下），不要放入 capabilities：\n");
		for (AiChatCapabilityId cap : AiChatCapabilityId.values()) {
			if (!cap.available()) {
				sb.append("- ").append(cap.id()).append("：").append(cap.label()).append('\n');
			}
		}
		sb.append(
				"""

				输出 JSON 字段（不要 markdown）：
				- capabilities：本轮实际使用的能力 id 数组；纯闲聊至少含 chat
				- unsupported：用户想要但尚未上线的能力 id 数组，无则 []
				- taskListStatus：使用 assistant_tasks 且需筛选时填 OPEN/DONE/CANCELLED，否则 null
				- reason：一句话说明路由理由（内部用）

				规则：
				- 查/记/改助手待办 → capabilities 含 assistant_tasks（查也必须带，不能只加载摘要）
				- 用户要制定/复习/学习计划、备考方案、一个月计划等 → capabilities 含 plan_proposal（须调用 propose_growth_plan，勿直接 create_task 批量落库）
				- 续聊或指代上文 → 含 chat_history
				- 需要称呼或个性化 → 含 user_profile
				- 用户要直接操作已入库的成长计划任务表、里程碑 CRUD → 写 unsupported（growth_plan_tasks/goals），不要编造数据
				""");
		return sb.toString();
	}

	public static String buildUnsupportedOnlyReply(List<AiChatCapabilityId> unsupported) {
		String names =
				unsupported.stream().map(AiChatCapabilityId::label).collect(Collectors.joining("、"));
		return """
				抱歉，这方面我还不是万能的，暂时完不成你要的「%s」。
				你的需求我已经记下了，会提交给开发同学排期，上线后再跟你说。

				我现在能帮你的是：记待办、查/改任务，或者随便聊聊。比如「帮我记明天下午 3 点开会」「查我未来几天的待办」。
				"""
				.formatted(names)
				.trim();
	}

	public static String buildUnsupportedHintForExecute(List<AiChatCapabilityId> unsupported) {
		if (unsupported == null || unsupported.isEmpty()) {
			return null;
		}
		String names =
				unsupported.stream().map(AiChatCapabilityId::label).collect(Collectors.joining("、"));
		return "用户还想要尚未上线的「"
				+ names
				+ "」。请先用一两句轻松中文说明：你不是万能的、这个暂时做不了、已反馈开发排期；再处理你已具备的能力（如助手待办）。不要编造未上线功能的数据。";
	}

	public static List<AiChatCapabilityId> parseIdList(Iterable<String> ids) {
		List<AiChatCapabilityId> out = new ArrayList<>();
		if (ids == null) {
			return out;
		}
		for (String raw : ids) {
			fromId(raw).ifPresent(out::add);
		}
		return out;
	}

	public static java.util.Optional<AiChatCapabilityId> fromId(String raw) {
		return AiChatCapabilityId.fromId(raw);
	}

	public static List<String> toIdStrings(List<AiChatCapabilityId> caps) {
		return caps.stream().map(AiChatCapabilityId::id).toList();
	}

	public static boolean isBlankReason(String reason) {
		return !StringUtils.hasText(reason);
	}
}
