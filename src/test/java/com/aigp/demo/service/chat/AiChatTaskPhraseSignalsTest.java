package com.aigp.demo.service.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aigp.demo.domain.enums.AiChatRoundAction;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AiChatTaskPhraseSignalsTest {

	@Test
	void statusQuery_notTreatedAsCompleteStatement() {
		assertTrue(AiChatTaskPhraseSignals.isTaskStatusQuery("我今天晚上 AI 成长汇报会议完成了吗？我忘记了"));
		assertFalse(AiChatTaskPhraseSignals.isTaskCompleteStatement("我今天晚上 AI 成长汇报会议完成了吗？我忘记了"));
	}

	@Test
	void completeStatement_recognized() {
		assertTrue(AiChatTaskPhraseSignals.isTaskCompleteStatement("两个都完成了"));
		assertFalse(AiChatTaskPhraseSignals.isTaskStatusQuery("两个都完成了"));
	}

	@Test
	void mutationRequest_excludesStatusQuery() {
		assertTrue(AiChatTaskPhraseSignals.looksLikeTaskMutationRequest("帮我记明天下午3点开会"));
		assertFalse(AiChatTaskPhraseSignals.looksLikeTaskMutationRequest("会议完成了吗？"));
	}
}

class AiChatDeterministicRouteStatusQueryTest {

	@Test
	void completedQuestion_routesToStatusQuery_notComplete() {
		var route = AiChatDeterministicRoute.tryRoute(
				"我今天晚上 AI 成长汇报会议完成了吗？我忘记了", null, true, false);
		assertTrue(route.isPresent());
		assertEquals("确定性路由：查询任务完成状态", route.get().reason());
		assertTrue(route.get().hasCapability(AiChatCapabilityId.ASSISTANT_TASKS));
	}

	@Test
	void bothCompleted_routesToMarkComplete() {
		var route = AiChatDeterministicRoute.tryRoute("两个都完成了", null, true, false);
		assertTrue(route.isPresent());
		assertEquals("确定性路由：标记任务完成", route.get().reason());
	}
}

class AiChatExecutionToolGuardTest {

	@Test
	void retriesWhenFabricatedCreateReply() {
		var route = new AiChatRoutePlan(
				Set.of(AiChatCapabilityId.CHAT, AiChatCapabilityId.ASSISTANT_TASKS),
				java.util.List.of(),
				null,
				"确定性路由：创建助手待办/提醒");
		var guard = AiChatExecutionToolGuard.from(route, "应调用 create_task");
		assertTrue(guard.shouldRetryMissingMutationTools("已帮你安排好了！📋", AiChatRoundAction.CHAT_ONLY));
		guard.markRetried();
		assertFalse(guard.shouldRetryMissingMutationTools("已帮你安排好了！📋", AiChatRoundAction.CHAT_ONLY));
		assertTrue(guard.needsFallbackAfterMissingTools("已帮你安排好了！📋", AiChatRoundAction.CHAT_ONLY));
	}

	@Test
	void noRetryWhenToolsWereUsed() {
		var route = new AiChatRoutePlan(
				Set.of(AiChatCapabilityId.ASSISTANT_TASKS),
				java.util.List.of(),
				"OPEN",
				"确定性路由：标记任务完成");
		var guard = AiChatExecutionToolGuard.from(route, null);
		assertFalse(guard.shouldRetryMissingMutationTools("好的，已完成", AiChatRoundAction.REMINDER_COMPLETED));
	}

	@Test
	void infersMutationFromUser_serviceContextWhenIntentSkipped() {
		com.aigp.demo.service.AiChatRequestContext.setUserMessage("帮我记明天下午3点开会");
		try {
			var route = new AiChatRoutePlan(
					Set.of(AiChatCapabilityId.CHAT, AiChatCapabilityId.ASSISTANT_TASKS),
					java.util.List.of(),
					null,
					"LLM 规划路由");
			var guard = AiChatExecutionToolGuard.from(route, null);
			assertTrue(guard.shouldRetryMissingMutationTools("已帮你记好了", AiChatRoundAction.CHAT_ONLY));
		} finally {
			com.aigp.demo.service.AiChatRequestContext.clear();
		}
	}
}
