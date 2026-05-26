package com.aigp.demo.service.chat;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.util.StringUtils;

/**
 * 常见任务类话术的规则路由：跳过 plan/intent 两次 LLM，直接进入执行阶段。
 * <p>与 {@link AiChatFastPath} 问候快路径互补；附图、制定计划草案等仍走完整规划。
 */
public final class AiChatDeterministicRoute {

	/** 规则路由适用的最长用户输入（字符） */
	private static final int MAX_MESSAGE_CHARS = 96;

	private static final Pattern TASK_LIST_QUERY = Pattern.compile(
			".*(未来|接下来|这几天|最近|哪些|列出|查看|查询|看看|有什么|有啥|帮我看|查一下|查下)"
					+ ".*(任务|待办|提醒|安排|计划).*",
			Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

	private static final Pattern TASK_LIST_SHORT = Pattern.compile(
			"^(我)?(的)?(任务|待办|提醒)(列表|清单)?$",
			Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

	private static final Pattern TASK_CREATE = Pattern.compile(
			".*(提醒我|记得|别忘了|记一下|帮我记|帮我记录|记个|安排一下|记待办).*",
			Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

	private static final Pattern TASK_COMPLETE = Pattern.compile(
			".*(完成了|做完了|搞定了|已完成|标记完成|任务完成).*",
			Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

	private static final Pattern TASK_DELETE = Pattern.compile(
			".*(取消|删除|删掉).*(任务|待办|提醒).*",
			Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

	private AiChatDeterministicRoute() {}

	/**
	 * @param hasImages 附图须走 VLM 与完整规划
	 */
	public static Optional<AiChatRoutePlan> tryRoute(
			String userMessage, String historySnippet, boolean sessionHasMessages, boolean hasImages) {
		if (hasImages) {
			return Optional.empty();
		}
		String msg = userMessage == null ? "" : userMessage.trim();
		if (!StringUtils.hasText(msg) || msg.length() > MAX_MESSAGE_CHARS) {
			return Optional.empty();
		}
		if (AiChatFastPath.containsPlanProposalSignals(msg)
				|| AiChatFastPath.containsPlanProposalSignals(historySnippet)) {
			return Optional.empty();
		}
		if (AiChatFastPath.containsUnsupportedFeatureSignals(msg)
				|| AiChatFastPath.containsUnsupportedFeatureSignals(historySnippet)) {
			return Optional.empty();
		}

		Set<AiChatCapabilityId> caps = baseCaps(sessionHasMessages, historySnippet);

		if (looksLikeTaskList(msg)) {
			caps.add(AiChatCapabilityId.ASSISTANT_TASKS);
			caps.add(AiChatCapabilityId.GROWTH_PLAN_TASKS);
			return Optional.of(new AiChatRoutePlan(
					caps, List.of(), "OPEN", "确定性路由：查询任务列表"));
		}
		if (TASK_CREATE.matcher(msg).matches()) {
			caps.add(AiChatCapabilityId.ASSISTANT_TASKS);
			caps.add(AiChatCapabilityId.USER_PROFILE);
			return Optional.of(new AiChatRoutePlan(
					caps, List.of(), "OPEN", "确定性路由：创建助手待办/提醒"));
		}
		if (TASK_COMPLETE.matcher(msg).matches()) {
			caps.add(AiChatCapabilityId.ASSISTANT_TASKS);
			caps.add(AiChatCapabilityId.GROWTH_PLAN_TASKS);
			return Optional.of(new AiChatRoutePlan(
					caps, List.of(), "OPEN", "确定性路由：标记任务完成"));
		}
		if (TASK_DELETE.matcher(msg).matches()) {
			caps.add(AiChatCapabilityId.ASSISTANT_TASKS);
			return Optional.of(new AiChatRoutePlan(
					caps, List.of(), null, "确定性路由：取消助手待办"));
		}
		return Optional.empty();
	}

	private static Set<AiChatCapabilityId> baseCaps(boolean sessionHasMessages, String historySnippet) {
		Set<AiChatCapabilityId> caps = new LinkedHashSet<>();
		caps.add(AiChatCapabilityId.CHAT);
		if (sessionHasMessages || StringUtils.hasText(historySnippet)) {
			caps.add(AiChatCapabilityId.CHAT_HISTORY);
		}
		return caps;
	}

	private static boolean looksLikeTaskList(String msg) {
		if (TASK_LIST_SHORT.matcher(msg).matches()) {
			return true;
		}
		if (TASK_LIST_QUERY.matcher(msg).matches()) {
			return true;
		}
		String lower = msg.toLowerCase(Locale.ROOT);
		return (lower.contains("待办") || lower.contains("任务") || lower.contains("提醒"))
				&& (lower.contains("查") || lower.contains("列") || lower.contains("有哪些") || lower.contains("看看"));
	}
}
