package com.aigp.demo.service.chat;

import com.aigp.demo.domain.enums.AiChatRoundAction;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 统计单轮对话执行环内所有助手任务工具调用，汇总为一个 {@link AiChatRoundAction}。
 */
public class AiChatRoundActionTracker {

	private AiChatRoundAction action = AiChatRoundAction.CHAT_ONLY;
	private final ObjectMapper objectMapper;

	public AiChatRoundActionTracker(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	/** 记录一次工具调用（create_task / update_task / delete_task 等）。 */
	public void record(String toolName, String argumentsJson) {
		AiChatRoundAction next = AiChatRoundActionResolver.fromToolCall(toolName, argumentsJson, objectMapper);
		action = AiChatRoundActionResolver.merge(action, next);
	}

	public AiChatRoundAction get() {
		return action;
	}
}
