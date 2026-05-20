package com.aigp.demo.service.chat;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.util.StringUtils;

/**
 * 确定性快速路由：闲聊/短句跳过 plan LLM，显著降低首包延迟。
 */
public final class AiChatFastPath {

	private static final int MAX_FAST_PATH_MESSAGE_CHARS = 48;

	private static final Pattern SIMPLE_GREETING = Pattern.compile(
			"^(你好|您好|hi|hello|hey|嗨|早上好|下午好|晚上好|在吗|哈喽|谢谢|多谢|好的|ok|okay)[\\s!！?？。.~～,，]*$",
			Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

	private AiChatFastPath() {}

	/**
	 * @param hasImages 本轮是否附图（附图走 VLM，需完整规划）
	 */
	public static Optional<AiChatRoutePlan> tryPlan(
			String userMessage, String historySnippet, boolean sessionHasMessages, boolean hasImages) {
		if (hasImages) {
			return Optional.empty();
		}
		String msg = userMessage == null ? "" : userMessage.trim();
		if (!StringUtils.hasText(msg)) {
			return Optional.empty();
		}
		if (needsFullPlanning(msg, historySnippet)) {
			return Optional.empty();
		}
		Set<AiChatCapabilityId> caps = new LinkedHashSet<>();
		caps.add(AiChatCapabilityId.CHAT);
		if (sessionHasMessages || StringUtils.hasText(historySnippet)) {
			caps.add(AiChatCapabilityId.CHAT_HISTORY);
		}
		String reason =
				SIMPLE_GREETING.matcher(msg).matches()
						? "快速路由：问候/寒暄"
						: "快速路由：短句闲聊";
		return Optional.of(new AiChatRoutePlan(caps, List.of(), null, reason));
	}

	private static boolean needsFullPlanning(String msg, String historySnippet) {
		if (msg.length() > MAX_FAST_PATH_MESSAGE_CHARS) {
			return true;
		}
		if (containsTaskSignals(msg) || containsTaskSignals(historySnippet)) {
			return true;
		}
		if (containsUnsupportedFeatureSignals(msg) || containsUnsupportedFeatureSignals(historySnippet)) {
			return true;
		}
		if (containsPlanProposalSignals(msg) || containsPlanProposalSignals(historySnippet)) {
			return true;
		}
		return false;
	}

	private static boolean containsTaskSignals(String text) {
		if (!StringUtils.hasText(text)) {
			return false;
		}
		String t = text.toLowerCase(Locale.ROOT);
		return t.contains("待办")
				|| t.contains("任务")
				|| t.contains("提醒")
				|| t.contains("记得")
				|| t.contains("别忘了")
				|| t.contains("记一下")
				|| t.contains("帮我记")
				|| t.contains("记录")
				|| t.contains("安排")
				|| t.contains("开会")
				|| t.contains("计划")
				|| t.contains("列出")
				|| t.contains("查一下")
				|| t.contains("查询")
				|| t.contains("取消")
				|| t.contains("完成")
				|| t.contains("todo")
				|| t.contains("list_tasks")
				|| t.contains("create_task");
	}

	private static boolean containsUnsupportedFeatureSignals(String text) {
		if (!StringUtils.hasText(text)) {
			return false;
		}
		return text.contains("成长计划")
				|| text.contains("里程碑")
				|| text.contains("目标拆解")
				|| text.contains("长期目标");
	}

	private static boolean containsPlanProposalSignals(String text) {
		if (!StringUtils.hasText(text)) {
			return false;
		}
		return text.contains("复习计划")
				|| text.contains("学习计划")
				|| text.contains("制定计划")
				|| text.contains("备考")
				|| text.contains("复习方案")
				|| text.contains("学习方案")
				|| (text.contains("计划") && (text.contains("考试") || text.contains("六级") || text.contains("四级")));
	}
}
