package com.aigp.demo.service.chat;

import com.aigp.demo.domain.enums.AiChatRoundAction;
import com.aigp.demo.service.AiChatRequestContext;
import com.aigp.demo.service.chat.AiChatStructuredIntent.AssistantTaskOp;
import java.util.regex.Pattern;
import org.springframework.util.StringUtils;

/**
 * 执行环兜底：模型未调任务工具却口头承诺已查询/已创建/已完成时，强制补调一轮；仍失败则返回诚实话术。
 */
public final class AiChatExecutionToolGuard {

	private static final Pattern FABRICATED_MUTATION_REPLY =
			Pattern.compile(
					".*(已帮你安排|已帮你记录|已帮你记|已帮你设置|已帮你创建|已为您创建|已经记|已经创建|记住了|"
							+ "已标记为.{0,12}完成|已帮你取消|已帮你把.{0,24}标记|"
							+ "都已经完成|都标记为完成|创建好了|安排好了|设置好提醒|提醒已设置).*",
					Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.DOTALL);

	/** 未调 list_tasks 却像在罗列待办（编号列表、以下任务等）。 */
	private static final Pattern FABRICATED_QUERY_REPLY =
			Pattern.compile(
					".*(?:(?:以下|如下|这些是|你有|您有|共有|一共|总共).{0,8}(?:待办|任务|提醒)"
							+ "|(?:\\d+[.、．]\\s*.{1,40}(?:待办|任务|提醒))"
							+ "|(?:未来.{0,6}(?:天|日).{0,12}(?:待办|任务|安排|计划))"
							+ "|(?:查(?:到|询)了?).{0,6}(?:待办|任务)).*",
					Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.DOTALL);

	private final boolean expectMutation;
	private final boolean expectQuery;
	private boolean mutationRetried;
	private boolean queryRetried;

	private AiChatExecutionToolGuard(boolean expectMutation, boolean expectQuery) {
		this.expectMutation = expectMutation;
		this.expectQuery = expectQuery;
	}

	public static AiChatExecutionToolGuard from(AiChatRoutePlan route, AiChatStructuredIntent structuredIntent) {
		if (route == null || !route.hasCapability(AiChatCapabilityId.ASSISTANT_TASKS)) {
			return new AiChatExecutionToolGuard(false, false);
		}
		String userMessage = AiChatRequestContext.getUserMessage();
		boolean mutation =
				inferMutationFromRoute(route)
						|| AiChatIntentSignals.suggestsTaskMutation(structuredIntent)
						|| AiChatTaskPhraseSignals.looksLikeTaskMutationRequest(userMessage);
		boolean query =
				inferQueryFromRoute(route)
						|| inferQueryFromStructuredIntent(structuredIntent)
						|| AiChatTaskPhraseSignals.looksLikeTaskListRequest(userMessage);
		return new AiChatExecutionToolGuard(mutation, query);
	}

	/**
	 * 变更类（创建/完成/取消）未落库却口头声称成功时，是否追加补调轮。
	 * <p>含「先 list_tasks 再口头已创建」：此时 roundAction 为 REMINDER_QUERIED 也会触发。
	 */
	public boolean shouldRetryMissingMutationTools(String assistantContent, AiChatRoundAction roundAction) {
		if (mutationRetried || !expectMutation || mutationPersisted(roundAction)) {
			return false;
		}
		if (!StringUtils.hasText(assistantContent)) {
			return true;
		}
		return FABRICATED_MUTATION_REPLY.matcher(assistantContent.trim()).find();
	}

	/** 查询类未调 list_tasks/get_task 却像在罗列待办时，是否追加补调轮。 */
	public boolean shouldRetryMissingQueryTools(String assistantContent, AiChatRoundAction roundAction) {
		if (queryRetried || !expectQuery || queryPersisted(roundAction)) {
			return false;
		}
		if (!StringUtils.hasText(assistantContent)) {
			return true;
		}
		return FABRICATED_QUERY_REPLY.matcher(assistantContent.trim()).find();
	}

	public void markMutationRetried() {
		this.mutationRetried = true;
	}

	public void markQueryRetried() {
		this.queryRetried = true;
	}

	public String retryMutationNudge() {
		return AiChatPrompts.EXECUTION_RETRY_MISSING_MUTATION_SYSTEM;
	}

	public String retryQueryNudge() {
		return AiChatPrompts.EXECUTION_RETRY_MISSING_QUERY_SYSTEM;
	}

	public boolean needsFallbackAfterMissingMutation(String assistantContent, AiChatRoundAction roundAction) {
		if (!expectMutation || mutationPersisted(roundAction)) {
			return false;
		}
		if (!StringUtils.hasText(assistantContent)) {
			return true;
		}
		return FABRICATED_MUTATION_REPLY.matcher(assistantContent.trim()).find();
	}

	public boolean needsFallbackAfterMissingQuery(String assistantContent, AiChatRoundAction roundAction) {
		if (!expectQuery || queryPersisted(roundAction)) {
			return false;
		}
		if (!StringUtils.hasText(assistantContent)) {
			return true;
		}
		return FABRICATED_QUERY_REPLY.matcher(assistantContent.trim()).find();
	}

	private static boolean mutationPersisted(AiChatRoundAction roundAction) {
		return roundAction == AiChatRoundAction.REMINDER_CREATED
				|| roundAction == AiChatRoundAction.REMINDER_UPDATED
				|| roundAction == AiChatRoundAction.REMINDER_DELETED
				|| roundAction == AiChatRoundAction.REMINDER_COMPLETED;
	}

	private static boolean queryPersisted(AiChatRoundAction roundAction) {
		return roundAction == AiChatRoundAction.REMINDER_QUERIED;
	}

	private static boolean inferMutationFromRoute(AiChatRoutePlan route) {
		String reason = route.reason();
		if (!StringUtils.hasText(reason)) {
			return false;
		}
		if (reason.contains("查询任务")) {
			return false;
		}
		return reason.contains("创建")
				|| reason.contains("标记任务完成")
				|| reason.contains("取消助手待办")
				|| (reason.contains("取消") && reason.contains("待办"));
	}

	private static boolean inferQueryFromRoute(AiChatRoutePlan route) {
		String reason = route.reason();
		if (!StringUtils.hasText(reason)) {
			return false;
		}
		return reason.contains("查询任务列表")
				|| reason.contains("查询任务完成状态")
				|| (reason.contains("查询") && reason.contains("任务"));
	}

	private static boolean inferQueryFromStructuredIntent(AiChatStructuredIntent intent) {
		if (intent == null) {
			return false;
		}
		return intent.assistantTaskOp() == AssistantTaskOp.LIST
				|| intent.assistantTaskOp() == AssistantTaskOp.GET
				|| intent.assistantTaskOp() == AssistantTaskOp.COMPLETE_QUERY;
	}
}
