package com.aigp.demo.service.chat;

import com.aigp.demo.domain.enums.AiChatRoundAction;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.util.StringUtils;

/**
 * 根据本轮实际调用的助手任务工具名与参数，解析 {@link AiChatRoundAction}。
 */
public final class AiChatRoundActionResolver {

	private AiChatRoundActionResolver() {}

	/**
	 * 将单次工具调用映射为动作；非助手任务工具时返回 {@link AiChatRoundAction#CHAT_ONLY}。
	 */
	public static AiChatRoundAction fromToolCall(String toolName, String argumentsJson, ObjectMapper objectMapper) {
		if (!StringUtils.hasText(toolName)) {
			return AiChatRoundAction.CHAT_ONLY;
		}
		return switch (toolName) {
			case "list_tasks", "get_task" -> AiChatRoundAction.REMINDER_QUERIED;
			case "create_task" -> AiChatRoundAction.REMINDER_CREATED;
			case "delete_task" -> AiChatRoundAction.REMINDER_DELETED;
			case "update_task" -> resolveUpdateAction(argumentsJson, objectMapper);
			default -> AiChatRoundAction.CHAT_ONLY;
		};
	}

	/**
	 * 合并多轮/多次工具调用结果：取优先级更高者（完成 &gt; 删除 &gt; 新建 &gt; 修改 &gt; 查询 &gt; 纯对话）。
	 */
	public static AiChatRoundAction merge(AiChatRoundAction current, AiChatRoundAction next) {
		if (next == null || next == AiChatRoundAction.CHAT_ONLY) {
			return current == null ? AiChatRoundAction.CHAT_ONLY : current;
		}
		if (current == null || current == AiChatRoundAction.CHAT_ONLY) {
			return next;
		}
		return precedence(next) >= precedence(current) ? next : current;
	}

	private static AiChatRoundAction resolveUpdateAction(String argumentsJson, ObjectMapper objectMapper) {
		String status = parseStatus(argumentsJson, objectMapper);
		if (StringUtils.hasText(status) && "DONE".equalsIgnoreCase(status.trim())) {
			return AiChatRoundAction.REMINDER_COMPLETED;
		}
		return AiChatRoundAction.REMINDER_UPDATED;
	}

	private static String parseStatus(String argumentsJson, ObjectMapper objectMapper) {
		if (!StringUtils.hasText(argumentsJson) || objectMapper == null) {
			return null;
		}
		try {
			JsonNode node = objectMapper.readTree(argumentsJson);
			JsonNode status = node.path("status");
			if (status.isMissingNode() || status.isNull()) {
				return null;
			}
			return status.asText();
		} catch (Exception ignored) {
			return null;
		}
	}

	private static int precedence(AiChatRoundAction action) {
		return switch (action) {
			case REMINDER_COMPLETED -> 50;
			case REMINDER_DELETED -> 40;
			case REMINDER_CREATED -> 30;
			case REMINDER_UPDATED -> 20;
			case REMINDER_QUERIED -> 10;
			case CHAT_ONLY -> 0;
		};
	}
}
