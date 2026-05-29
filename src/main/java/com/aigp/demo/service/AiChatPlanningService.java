package com.aigp.demo.service;

import com.aigp.demo.config.AppProperties;
import com.aigp.demo.service.chat.AiChatCapabilityCatalog;
import com.aigp.demo.service.chat.AiChatFastPath;
import com.aigp.demo.service.chat.AiChatPrompts;
import com.aigp.demo.service.chat.AiChatIntentResolver;
import com.aigp.demo.service.chat.AiChatRoutePlan;
import com.aigp.demo.service.chat.AiChatRouteResolver;
import com.aigp.demo.service.chat.AiChatStructuredIntent;
import java.util.Optional;
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

	private final AppProperties appProperties;
	private final OpenAiCompatibleChatClient openAiCompatibleChatClient;
	private final AiChatPipelineDebugLog pipelineDebugLog;
	private final AiChatRouteResolver routeResolver;
	private final AiChatIntentResolver intentResolver;

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
		String planSystem =
				AiChatPrompts.routePlanSystemPrompt(AiChatCapabilityCatalog.plannerCapabilityListAppendix());
		List<Map<String, Object>> messages = List.of(
				Map.of("role", "system", "content", planSystem),
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
	/**
	 * 意图分析：要求模型输出结构化 JSON；解析失败则返回 empty（不拼自由短文进 execute）。
	 */
	public Optional<AiChatStructuredIntent> analyzeUserIntent(
			AppProperties.ChatProvider provider,
			String userMessage,
			List<Map<String, Object>> sessionHistory) {
		if (!appProperties.getChat().isMultiPhaseEnabled()) {
			return Optional.empty();
		}
		List<Map<String, Object>> messages = new ArrayList<>();
		messages.add(Map.of("role", "system", "content", AiChatPrompts.INTENT_ANALYSIS_SYSTEM));
		if (sessionHistory != null && !sessionHistory.isEmpty()) {
			messages.addAll(sessionHistory);
			pipelineDebugLog.step("intent", "带入当前会话历史 %s 条", sessionHistory.size());
		}
		messages.add(Map.of("role", "user", "content", userMessage));
		try {
			ChatCompletionResult result = openAiCompatibleChatClient.chat(provider, messages, null, "intent");
			Optional<AiChatStructuredIntent> parsed = intentResolver.parse(result.content());
			if (parsed.isPresent()) {
				pipelineDebugLog.step("intent", "结构化结果: %s", parsed.get());
			} else {
				pipelineDebugLog.step("intent", "JSON 解析失败，跳过意图增强 raw=%s", truncate(result.content(), 200));
			}
			return parsed;
		} catch (Exception e) {
			pipelineDebugLog.step("intent", "失败，跳过: %s", e.getMessage());
			log.warn("意图分析失败，跳过该轮: {}", e.getMessage());
			return Optional.empty();
		}
	}

	private static String truncate(String s, int max) {
		if (s == null) {
			return null;
		}
		String t = s.trim();
		return t.length() <= max ? t : t.substring(0, max);
	}

}
