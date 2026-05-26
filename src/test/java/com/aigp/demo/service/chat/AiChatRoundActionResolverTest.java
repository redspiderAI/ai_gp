package com.aigp.demo.service.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.aigp.demo.domain.enums.AiChatRoundAction;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class AiChatRoundActionResolverTest {

	private final ObjectMapper objectMapper = new ObjectMapper();

	@Test
	void createTask_mapsToReminderCreated() {
		assertEquals(
				AiChatRoundAction.REMINDER_CREATED,
				AiChatRoundActionResolver.fromToolCall("create_task", "{\"title\":\"喝水\"}", objectMapper));
	}

	@Test
	void deleteTask_mapsToReminderDeleted() {
		assertEquals(
				AiChatRoundAction.REMINDER_DELETED,
				AiChatRoundActionResolver.fromToolCall("delete_task", "{\"taskId\":1}", objectMapper));
	}

	@Test
	void updateTaskDone_mapsToReminderCompleted() {
		assertEquals(
				AiChatRoundAction.REMINDER_COMPLETED,
				AiChatRoundActionResolver.fromToolCall(
						"update_task", "{\"taskId\":1,\"status\":\"DONE\"}", objectMapper));
	}

	@Test
	void updateTaskOther_mapsToReminderUpdated() {
		assertEquals(
				AiChatRoundAction.REMINDER_UPDATED,
				AiChatRoundActionResolver.fromToolCall(
						"update_task", "{\"taskId\":1,\"dueAt\":\"2026-05-26 21:00\"}", objectMapper));
	}

	@Test
	void listTasks_mapsToReminderQueried() {
		assertEquals(
				AiChatRoundAction.REMINDER_QUERIED,
				AiChatRoundActionResolver.fromToolCall("list_tasks", "{}", objectMapper));
	}

	@Test
	void getTask_mapsToReminderQueried() {
		assertEquals(
				AiChatRoundAction.REMINDER_QUERIED,
				AiChatRoundActionResolver.fromToolCall("get_task", "{\"taskId\":1}", objectMapper));
	}

	@Test
	void merge_prefersCreatedOverQueried() {
		assertEquals(
				AiChatRoundAction.REMINDER_CREATED,
				AiChatRoundActionResolver.merge(
						AiChatRoundAction.REMINDER_QUERIED, AiChatRoundAction.REMINDER_CREATED));
	}

	@Test
	void merge_prefersCompletedOverCreated() {
		assertEquals(
				AiChatRoundAction.REMINDER_COMPLETED,
				AiChatRoundActionResolver.merge(
						AiChatRoundAction.REMINDER_CREATED, AiChatRoundAction.REMINDER_COMPLETED));
	}
}
