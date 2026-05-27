package com.aigp.demo.service.chat;

import com.aigp.demo.domain.enums.AiChatRoundAction;
import com.aigp.demo.service.AiChatRequestContext;
import java.util.regex.Pattern;
import org.springframework.util.StringUtils;

/**
 * 执行环兜底：模型未调任务工具却声称已创建/完成/取消时，追加一轮强制补调；仍失败则返回诚实话术。
 */
public final class AiChatExecutionToolGuard {

	private static final Pattern FABRICATED_MUTATION_REPLY = Pattern.compile(
			".*(已帮你安排|已帮你记录|已帮你设置|已标记为.{0,12}完成|已帮你取消|已帮你把.{0,24}标记|"
					+ "都已经完成|都标记为完成|创建好了|安排好了|设置好提醒).*",
			Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.DOTALL);

	private static final String RETRY_SYSTEM_NUDGE =
			"【系统】上一轮回复未调用任何任务工具（tool_calls 为空），但正文声称已创建/完成/取消待办，违反规则。"
					+ "必须先调用 create_task、update_task、delete_task、list_tasks、complete_growth_task 等工具，"
					+ "再根据工具返回结果组织面向用户的回复；禁止仅文字声称已操作。";

	private final boolean expectMutation;
	private boolean retried;

	private AiChatExecutionToolGuard(boolean expectMutation) {
		this.expectMutation = expectMutation;
	}

	public static AiChatExecutionToolGuard from(AiChatRoutePlan route, String intentHint) {
		if (route == null || !route.hasCapability(AiChatCapabilityId.ASSISTANT_TASKS)) {
			return new AiChatExecutionToolGuard(false);
		}
		String userMessage = AiChatRequestContext.getUserMessage();
		boolean mutation = inferMutationFromRoute(route)
				|| AiChatIntentSignals.suggestsTaskMutation(intentHint)
				|| AiChatTaskPhraseSignals.looksLikeTaskMutationRequest(userMessage);
		return new AiChatExecutionToolGuard(mutation);
	}

	private static boolean inferMutationFromRoute(AiChatRoutePlan route) {
		String reason = route.reason();
		if (!StringUtils.hasText(reason)) {
			return false;
		}
		if (reason.contains("查询任务") || reason.contains("查询任务列表")) {
			return false;
		}
		return reason.contains("创建")
				|| reason.contains("标记任务完成")
				|| reason.contains("取消助手待办")
				|| reason.contains("取消");
	}

	/**
	 * 本轮无 tool_calls 且仍为 CHAT_ONLY 时，是否应追加一轮强制补调。
	 */
	public boolean shouldRetryMissingMutationTools(String assistantContent, AiChatRoundAction roundAction) {
		if (retried || !expectMutation || roundAction != AiChatRoundAction.CHAT_ONLY) {
			return false;
		}
		if (!StringUtils.hasText(assistantContent)) {
			return true;
		}
		return FABRICATED_MUTATION_REPLY.matcher(assistantContent.trim()).find();
	}

	public void markRetried() {
		this.retried = true;
	}

	public String retrySystemNudge() {
		return RETRY_SYSTEM_NUDGE;
	}

	/**
	 * 结束执行环时：若仍无工具调用且回复像「已操作」，改用诚实话术（含已重试仍失败的情况）。
	 */
	public boolean needsFallbackAfterMissingTools(String assistantContent, AiChatRoundAction roundAction) {
		if (!expectMutation || roundAction != AiChatRoundAction.CHAT_ONLY) {
			return false;
		}
		if (!StringUtils.hasText(assistantContent)) {
			return true;
		}
		return FABRICATED_MUTATION_REPLY.matcher(assistantContent.trim()).find();
	}

	public String fallbackWhenStillMissingTools() {
		return "抱歉，我这边没能成功写入或更新你的待办，请换个说法再试一次，"
				+ "例如「帮我定今晚 8 点的会议」或「把 id=48 和 49 都标记完成」。";
	}
}
