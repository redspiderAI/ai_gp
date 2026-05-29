package com.aigp.demo.service.chat;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aigp.demo.domain.enums.AiChatRoundAction;
import com.aigp.demo.service.AiChatRequestContext;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AiChatExecutionToolGuardTest {

	@Test
	void retriesWhenFabricatedCreateReply() {
		var route = new AiChatRoutePlan(
				Set.of(AiChatCapabilityId.CHAT, AiChatCapabilityId.ASSISTANT_TASKS),
				List.of(),
				null,
				"确定性路由：创建助手待办/提醒");
		var intent = new AiChatStructuredIntent(
				AiChatStructuredIntent.PrimaryGoal.ASSISTANT_TASK,
				AiChatStructuredIntent.AssistantTaskOp.CREATE,
				AiChatStructuredIntent.GrowthTaskOp.NONE,
				AiChatStructuredIntent.PlanOp.NONE,
				false,
				false);
		var guard = AiChatExecutionToolGuard.from(route, intent);
		assertTrue(guard.shouldRetryMissingMutationTools("已帮你安排好了！📋", AiChatRoundAction.CHAT_ONLY));
		guard.markMutationRetried();
		assertFalse(guard.shouldRetryMissingMutationTools("已帮你安排好了！📋", AiChatRoundAction.CHAT_ONLY));
		assertTrue(guard.needsFallbackAfterMissingMutation("已帮你安排好了！📋", AiChatRoundAction.CHAT_ONLY));
	}

	/** 先 list 再口头「已创建」：roundAction 为 REMINDER_QUERIED 时也应补调。 */
	@Test
	void retriesFabricatedCreateAfterListTasksOnly() {
		var route = new AiChatRoutePlan(
				Set.of(AiChatCapabilityId.CHAT, AiChatCapabilityId.ASSISTANT_TASKS),
				List.of(),
				"OPEN",
				"确定性路由：创建助手待办/提醒");
		var guard = AiChatExecutionToolGuard.from(route, null);
		assertTrue(guard.shouldRetryMissingMutationTools("好的，已帮你创建提醒。", AiChatRoundAction.REMINDER_QUERIED));
	}

	@Test
	void noRetryWhenCreateToolWasUsed() {
		var route = new AiChatRoutePlan(
				Set.of(AiChatCapabilityId.ASSISTANT_TASKS),
				List.of(),
				"OPEN",
				"确定性路由：标记任务完成");
		var guard = AiChatExecutionToolGuard.from(route, null);
		assertFalse(guard.shouldRetryMissingMutationTools("好的，已完成", AiChatRoundAction.REMINDER_COMPLETED));
	}

	@Test
	void retriesWhenFabricatedTaskListWithoutListTool() {
		var route = new AiChatRoutePlan(
				Set.of(AiChatCapabilityId.CHAT, AiChatCapabilityId.ASSISTANT_TASKS),
				List.of(),
				"OPEN",
				"确定性路由：查询任务列表");
		var guard = AiChatExecutionToolGuard.from(route, null);
		String fakeList = "你未来几天有以下待办：\n1. 交周报\n2. 开会";
		assertTrue(guard.shouldRetryMissingQueryTools(fakeList, AiChatRoundAction.CHAT_ONLY));
		guard.markQueryRetried();
		assertTrue(guard.needsFallbackAfterMissingQuery(fakeList, AiChatRoundAction.CHAT_ONLY));
	}

	@Test
	void noQueryRetryWhenListToolWasUsed() {
		var route = new AiChatRoutePlan(
				Set.of(AiChatCapabilityId.ASSISTANT_TASKS),
				List.of(),
				null,
				"确定性路由：查询任务列表");
		var guard = AiChatExecutionToolGuard.from(route, null);
		assertFalse(guard.shouldRetryMissingQueryTools("这是你的待办", AiChatRoundAction.REMINDER_QUERIED));
	}

	@Test
	void infersMutationFromUserMessageWhenIntentSkipped() {
		AiChatRequestContext.setUserMessage("帮我记明天下午3点开会");
		try {
			var route = new AiChatRoutePlan(
					Set.of(AiChatCapabilityId.CHAT, AiChatCapabilityId.ASSISTANT_TASKS),
					List.of(),
					null,
					"LLM 规划路由");
			var guard = AiChatExecutionToolGuard.from(route, null);
			assertTrue(guard.shouldRetryMissingMutationTools("已帮你记好了", AiChatRoundAction.CHAT_ONLY));
		} finally {
			AiChatRequestContext.clear();
		}
	}
}
