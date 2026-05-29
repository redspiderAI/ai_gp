package com.aigp.demo.service.chat;

import com.aigp.demo.config.AppProperties;
import com.aigp.demo.config.AppProperties.ChatProvider;
import com.aigp.demo.service.AiChatPlanningService;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 对话流水线阶段 1：判断意图（路由能力 + 可选结构化意图分析）。
 */
@Component
public class AiChatIntentPhase {

	private final AppProperties appProperties;
	private final AiChatPlanningService aiChatPlanningService;
	private final AiChatRouteResolver routeResolver;

	public AiChatIntentPhase(
			AppProperties appProperties,
			AiChatPlanningService aiChatPlanningService,
			AiChatRouteResolver routeResolver) {
		this.appProperties = appProperties;
		this.aiChatPlanningService = aiChatPlanningService;
		this.routeResolver = routeResolver;
	}

	/**
	 * 阶段 1 结果：定稿路由与可选结构化意图（解析失败则为 empty）。
	 */
	public record Result(AiChatRoutePlan route, Optional<AiChatStructuredIntent> structuredIntent) {}

	/**
	 * 解析本轮路由：plan / 快速 / 确定性 → refine →（按策略）结构化 intent LLM。
	 */
	public Result resolve(
			ChatProvider providerConfig,
			String userMessage,
			String historySnippet,
			List<Map<String, Object>> sessionHistoryForIntent,
			boolean sessionHasMessages,
			boolean hasImagesThisTurn) {
		AiChatRoutePlan route = aiChatPlanningService.planRoute(
				providerConfig, userMessage, historySnippet, sessionHasMessages, hasImagesThisTurn);
		route = routeResolver.refine(route, userMessage, historySnippet, sessionHasMessages);

		Optional<AiChatStructuredIntent> structuredIntent = Optional.empty();
		if (AiChatIntentAnalysisPolicy.shouldAnalyzeIntent(appProperties.getChat(), route)) {
			structuredIntent =
					aiChatPlanningService.analyzeUserIntent(providerConfig, userMessage, sessionHistoryForIntent);
			route = alignRouteWithStructuredIntent(route, structuredIntent);
		}
		return new Result(route, structuredIntent);
	}

	private static AiChatRoutePlan alignRouteWithStructuredIntent(
			AiChatRoutePlan route, Optional<AiChatStructuredIntent> structuredIntent) {
		if (route == null || structuredIntent.isEmpty()) {
			return route;
		}
		AiChatStructuredIntent intent = structuredIntent.get();
		Set<AiChatCapabilityId> caps = new LinkedHashSet<>(route.capabilities());
		boolean changed = false;
		if (intent.suggestsAssistantTaskTools() && !route.hasCapability(AiChatCapabilityId.ASSISTANT_TASKS)) {
			caps.add(AiChatCapabilityId.ASSISTANT_TASKS);
			changed = true;
		}
		if (intent.suggestsPlanProposalTools() && !route.hasCapability(AiChatCapabilityId.PLAN_PROPOSAL)) {
			caps.add(AiChatCapabilityId.PLAN_PROPOSAL);
			changed = true;
		}
		if (intent.suggestsGrowthPlanTools() && !route.hasCapability(AiChatCapabilityId.GROWTH_PLAN_TASKS)) {
			caps.add(AiChatCapabilityId.GROWTH_PLAN_TASKS);
			changed = true;
		}
		if (!changed) {
			return route;
		}
		return new AiChatRoutePlan(caps, route.unsupported(), route.taskListStatus(), route.reason());
	}
}
