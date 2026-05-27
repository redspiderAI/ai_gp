package com.aigp.demo.service.chat;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aigp.demo.config.AppProperties;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AiChatIntentAnalysisPolicyTest {

	@Test
	void defaultSkipsIntentForAssistantTasksOnly() {
		AppProperties.Chat chat = new AppProperties.Chat();
		chat.setMultiPhaseEnabled(true);
		chat.setIntentAnalysisEnabled(false);
		AiChatRoutePlan route = routeWith(AiChatCapabilityId.CHAT, AiChatCapabilityId.ASSISTANT_TASKS);
		assertFalse(AiChatIntentAnalysisPolicy.shouldAnalyzeIntent(chat, route));
	}

	@Test
	void defaultRunsIntentForPlanProposal() {
		AppProperties.Chat chat = new AppProperties.Chat();
		chat.setMultiPhaseEnabled(true);
		chat.setIntentAnalysisEnabled(false);
		AiChatRoutePlan route = routeWith(
				AiChatCapabilityId.CHAT, AiChatCapabilityId.PLAN_PROPOSAL, AiChatCapabilityId.USER_PROFILE);
		assertTrue(AiChatIntentAnalysisPolicy.shouldAnalyzeIntent(chat, route));
	}

	@Test
	void legacyFlagRestoresFullIntentForAssistantTasks() {
		AppProperties.Chat chat = new AppProperties.Chat();
		chat.setMultiPhaseEnabled(true);
		chat.setIntentAnalysisEnabled(true);
		AiChatRoutePlan route = routeWith(AiChatCapabilityId.CHAT, AiChatCapabilityId.ASSISTANT_TASKS);
		assertTrue(AiChatIntentAnalysisPolicy.shouldAnalyzeIntent(chat, route));
	}

	@Test
	void skipsWhenFastOrDeterministicRoute() {
		AppProperties.Chat chat = new AppProperties.Chat();
		chat.setMultiPhaseEnabled(true);
		chat.setIntentAnalysisEnabled(true);
		AiChatRoutePlan route = new AiChatRoutePlan(
				Set.of(AiChatCapabilityId.CHAT, AiChatCapabilityId.ASSISTANT_TASKS),
				List.of(),
				"OPEN",
				"确定性路由：创建助手待办/提醒");
		assertFalse(AiChatIntentAnalysisPolicy.shouldAnalyzeIntent(chat, route));
	}

	private static AiChatRoutePlan routeWith(AiChatCapabilityId... caps) {
		Set<AiChatCapabilityId> set = new LinkedHashSet<>(List.of(caps));
		return new AiChatRoutePlan(set, List.of(), null, "LLM 规划路由");
	}
}
