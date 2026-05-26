package com.aigp.demo.service.chat;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AiChatFastPathTest {

	@Test
	void fastPathForGreeting() {
		assertTrue(
				AiChatFastPath.tryPlan("你好", null, false, false).isPresent());
		assertTrue(
				AiChatFastPath.tryPlan("hello", null, false, false).isPresent());
	}

	@Test
	void deterministicRouteForTaskListQuery() {
		assertTrue(
				AiChatFastPath.tryPlan("查我未来几天的待办", null, false, false).isPresent());
	}

	@Test
	void skipsWhenImages() {
		assertFalse(
				AiChatFastPath.tryPlan("你好", null, false, true).isPresent());
	}
}
