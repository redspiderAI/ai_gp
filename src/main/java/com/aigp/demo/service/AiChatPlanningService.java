package com.aigp.demo.service;

import com.aigp.demo.config.AppProperties;
import com.aigp.demo.service.chat.AiChatCapabilityCatalog;
import com.aigp.demo.service.chat.AiChatFastPath;
import com.aigp.demo.service.chat.AiChatRoutePlan;
import com.aigp.demo.service.chat.AiChatRouteResolver;
import com.aigp.demo.support.llm.AiChatPipelineDebugLog;
import com.aigp.demo.support.llm.ChatCompletionResult;
import com.aigp.demo.support.llm.OpenAiCompatibleChatClient;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiChatPlanningService {

	private static final String PLAN_PROMPT =
			"""
			你是「对话路由规划」模块。根据用户本轮输入（及可选的最近对话摘要），选择本轮要启用的能力。
			不要面向用户说话，只输出一个 JSON 对象。
			"""
					+ AiChatCapabilityCatalog.plannerSystemAppendix();

	private static final String INTENT_PROMPT =
			"""
			你是「意图分析」内部模块，输出仅供后端拼接进 system，用户看不到。
			下方 messages 中，system 之后、最后一条 user 之前的内容为【当前会话】内已有对话（不含本轮用户输入）。
			请结合会话历史理解指代（如「今天」「那个会」）后再分析。
			用 3～5 条短句说明：用户意图、是否应调用任务工具（create_task/list_tasks/update_task 等）、是否应调用 list_growth_tasks/complete_growth_task、是否应调用 propose_growth_plan。
			用户说「XX完成了」「做完了」：须写明先 list_growth_tasks 与 list_tasks 匹配，唯一匹配再 complete/update；多条须追问；日期默认今天。
			若为「提醒/记得/别忘了」且未给具体几点，须写明：create_task 应填 dueAt 并由执行模型推荐合理时刻，勿仅 dueDate=今天。
			用户问提醒如何送达时：后端会在到点自动推送（聊天消息 + 站内通知 + WebSocket），禁止写「无法主动推送/没有定时能力」。
			禁止：问候用户、向用户提问、以「好的」「请问」开头、输出任何面向用户的完整回复话术。
			不要编造未在上下文出现的事实。
			""";

	private final AppProperties appProperties;
	private final OpenAiCompatibleChatClient openAiCompatibleChatClient;
	private final AiChatPipelineDebugLog pipelineDebugLog;
	private final AiChatRouteResolver routeResolver;

	public AiChatRoutePlan planRoute(
			AppProperties.ChatProvider provider,
			String userMessage,
			String recentHistorySnippet,
			boolean sessionHasMessages,
			boolean hasImages) {
		if (!appProperties.getChat().isMultiPhaseEnabled()) {
			pipelineDebugLog.step("plan", "多阶段已关闭，使用默认路由");
			return AiChatRoutePlan.defaults();
		}
		if (appProperties.getChat().isFastPathEnabled()) {
			var fast = AiChatFastPath.tryPlan(userMessage, recentHistorySnippet, sessionHasMessages, hasImages);
			if (fast.isPresent()) {
				pipelineDebugLog.step("plan", "快速路由 capabilities=%s", fast.get().capabilities());
				return fast.get();
			}
		}
		StringBuilder userContent = new StringBuilder();
		userContent.append("用户本轮输入：\n").append(userMessage);
		if (StringUtils.hasText(recentHistorySnippet)) {
			userContent.append("\n\n最近对话摘要：\n").append(recentHistorySnippet);
		}
		List<Map<String, Object>> messages = List.of(
				Map.of("role", "system", "content", PLAN_PROMPT),
				Map.of("role", "user", "content", userContent.toString()));
		try {
			ChatCompletionResult result = openAiCompatibleChatClient.chat(provider, messages, null, "plan");
			AiChatRoutePlan route = routeResolver.parse(result.content());
			pipelineDebugLog.step("plan", "解析结果: capabilities=%s unsupported=%s", route.capabilities(), route.unsupported());
			return route;
		} catch (Exception e) {
			pipelineDebugLog.step("plan", "失败，回退默认: %s", e.getMessage());
			log.warn("路由规划解析失败，使用默认: {}", e.getMessage());
			return AiChatRoutePlan.defaults();
		}
	}

	/**
	 * @param sessionHistory 当前会话已落库的 user/assistant 消息（不含本轮输入），按时间升序
	 */
	public String analyzeUserIntent(
			AppProperties.ChatProvider provider,
			String userMessage,
			List<Map<String, Object>> sessionHistory) {
		if (!appProperties.getChat().isMultiPhaseEnabled()) {
			return null;
		}
		List<Map<String, Object>> messages = new ArrayList<>();
		messages.add(Map.of("role", "system", "content", INTENT_PROMPT));
		if (sessionHistory != null && !sessionHistory.isEmpty()) {
			messages.addAll(sessionHistory);
			pipelineDebugLog.step("intent", "带入当前会话历史 %s 条", sessionHistory.size());
		}
		messages.add(Map.of("role", "user", "content", userMessage));
		try {
			ChatCompletionResult result = openAiCompatibleChatClient.chat(provider, messages, null, "intent");
			String intent = StringUtils.hasText(result.content()) ? result.content().trim() : null;
			pipelineDebugLog.step("intent", "结果: %s", intent == null ? "<empty>" : intent);
			return intent;
		} catch (Exception e) {
			pipelineDebugLog.step("intent", "失败，跳过: %s", e.getMessage());
			log.warn("意图分析失败，跳过该轮: {}", e.getMessage());
			return null;
		}
	}

}
