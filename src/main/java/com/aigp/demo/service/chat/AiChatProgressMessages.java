package com.aigp.demo.service.chat;

import com.aigp.demo.service.chat.AiChatStructuredIntent.AssistantTaskOp;
import com.aigp.demo.service.chat.AiChatStructuredIntent.GrowthTaskOp;
import com.aigp.demo.service.chat.AiChatStructuredIntent.PlanOp;
import java.util.Locale;
import java.util.Optional;

/**
 * 对话进度固定话术：仅由后端根据 progressCode / 工具名查表，不让模型生成进度文案。
 */
public final class AiChatProgressMessages {

	private AiChatProgressMessages() {}

	public static String message(AiChatProgressCode code) {
		return code.defaultMessage();
	}

	/**
	 * 根据 OpenAI tool 名称解析进度码。
	 *
	 * @param toolName 如 create_task
	 */
	public static Optional<AiChatProgressCode> codeForTool(String toolName) {
		if (toolName == null || toolName.isBlank()) {
			return Optional.empty();
		}
		return switch (toolName.trim().toLowerCase(Locale.ROOT)) {
			case "list_tasks" -> Optional.of(AiChatProgressCode.TOOL_LIST_TASKS);
			case "get_task" -> Optional.of(AiChatProgressCode.TOOL_GET_TASK);
			case "create_task" -> Optional.of(AiChatProgressCode.TOOL_CREATE_TASK);
			case "update_task" -> Optional.of(AiChatProgressCode.TOOL_UPDATE_TASK);
			case "delete_task" -> Optional.of(AiChatProgressCode.TOOL_DELETE_TASK);
			case "list_growth_tasks" -> Optional.of(AiChatProgressCode.TOOL_LIST_GROWTH_TASKS);
			case "complete_growth_task" -> Optional.of(AiChatProgressCode.TOOL_COMPLETE_GROWTH_TASK);
			case "propose_growth_plan" -> Optional.of(AiChatProgressCode.TOOL_PROPOSE_PLAN);
			default -> Optional.empty();
		};
	}

	/** 结构化意图预判进度（可选，在真正调工具前安抚用户）。 */
	public static Optional<AiChatProgressCode> expectCode(AiChatStructuredIntent intent) {
		if (intent == null) {
			return Optional.empty();
		}
		if (intent.planOp() == PlanOp.PROPOSE) {
			return Optional.of(AiChatProgressCode.EXPECT_PLAN);
		}
		return switch (intent.assistantTaskOp()) {
			case CREATE -> Optional.of(AiChatProgressCode.EXPECT_CREATE_TASK);
			case LIST, GET, COMPLETE_QUERY -> Optional.of(AiChatProgressCode.EXPECT_LIST_TASKS);
			case UPDATE -> Optional.of(AiChatProgressCode.EXPECT_UPDATE_TASK);
			case DELETE -> Optional.of(AiChatProgressCode.TOOL_DELETE_TASK);
			case COMPLETE_MARK -> Optional.of(AiChatProgressCode.TOOL_COMPLETE_GROWTH_TASK);
			default -> {
				if (intent.growthTaskOp() == GrowthTaskOp.LIST) {
					yield Optional.of(AiChatProgressCode.TOOL_LIST_GROWTH_TASKS);
				}
				if (intent.growthTaskOp() == GrowthTaskOp.COMPLETE) {
					yield Optional.of(AiChatProgressCode.TOOL_COMPLETE_GROWTH_TASK);
				}
				yield Optional.empty();
			}
		};
	}
}
