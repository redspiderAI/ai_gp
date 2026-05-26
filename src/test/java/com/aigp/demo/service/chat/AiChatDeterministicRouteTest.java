package com.aigp.demo.service.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AiChatDeterministicRouteTest {

	@Test
	void futureTasksQuery_skipsPlanAndIntent() {
		var route = AiChatDeterministicRoute.tryRoute("我未来几天的任务", null, false, false);
		assertTrue(route.isPresent());
		assertTrue(route.get().hasCapability(AiChatCapabilityId.ASSISTANT_TASKS));
		assertTrue(route.get().hasCapability(AiChatCapabilityId.GROWTH_PLAN_TASKS));
		assertTrue(route.get().skipIntentAnalysis());
		assertEquals("OPEN", route.get().taskListStatus());
	}

	@Test
	void listTodoQuery_matches() {
		assertTrue(AiChatDeterministicRoute.tryRoute("查我未来几天的待办", null, false, false).isPresent());
	}
}
