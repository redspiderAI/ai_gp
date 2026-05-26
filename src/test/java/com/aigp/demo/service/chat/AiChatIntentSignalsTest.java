package com.aigp.demo.service.chat;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AiChatIntentSignalsTest {

	@Test
	void negatedToolMentionDoesNotSuggestTools() {
		String hint =
				"用户意图：问候。\n无需调用任务工具如create_task或list_tasks。\n没有上下文指代。";
		assertFalse(AiChatIntentSignals.suggestsTaskTools(hint));
	}

	@Test
	void positiveSuggestionEnablesTools() {
		assertTrue(AiChatIntentSignals.suggestsTaskTools("应调用 create_task 创建提醒，dueAt 必填。"));
	}

	@Test
	void negatedProposeDoesNotEnablePlanProposalDespiteOtherPositiveLines() {
		String hint =
				"""
				用户意图：查看未来几天的任务清单。
				应调用 list_tasks，筛选 dueDate 在未来几天范围内的任务。
				可同步调用 list_growth_tasks，一并展示成长任务。
				无需调用 complete_growth_task 或 propose_growth_plan。
				""";
		assertFalse(AiChatIntentSignals.suggestsPlanProposalTools(hint));
	}

	@Test
	void syncListGrowthTasksEnablesGrowthTools() {
		assertTrue(AiChatIntentSignals.suggestsGrowthPlanTools("可同步调用 list_growth_tasks，一并展示成长任务。"));
	}
}
