package com.aigp.demo.service;

import com.aigp.demo.service.growth.GrowthPlanProposalService;
import com.aigp.demo.support.llm.AiChatPipelineDebugLog;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AiChatToolExecutor {

	private final UserAssistantTaskService userAssistantTaskService;
	private final TaskService taskService;
	private final GrowthPlanProposalService growthPlanProposalService;
	private final ObjectMapper objectMapper;
	private final AiChatPipelineDebugLog pipelineDebugLog;

	public String execute(Long userId, String toolName, String argumentsJson) {
		return execute(userId, toolName, argumentsJson, "tool", 0);
	}

	public String execute(
			Long userId, String toolName, String argumentsJson, String debugPhase, int round) {
		try {
			JsonNode args = objectMapper.readTree(argumentsJson == null || argumentsJson.isBlank() ? "{}" : argumentsJson);
			String result = switch (toolName) {
				case "list_tasks" -> userAssistantTaskService.listTasksJson(
						userId,
						textOrNull(args.path("status")),
						textOrNull(args.path("dueFrom")),
						textOrNull(args.path("dueTo")));
				case "get_task" -> userAssistantTaskService.getTaskJson(userId, args.path("taskId").asLong());
				case "create_task" -> userAssistantTaskService.createTaskJson(
						userId,
						textOrNull(args.path("title")),
						textOrNull(args.path("description")),
						textOrNull(args.path("dueDate")),
						textOrNull(args.path("dueAt")),
						longList(args.path("imageAssetIds")));
				case "update_task" -> userAssistantTaskService.updateTaskJson(
						userId,
						args.path("taskId").asLong(),
						textOrNull(args.path("title")),
						textOrNull(args.path("description")),
						textOrNull(args.path("status")),
						args.has("dueDate") ? textOrNull(args.path("dueDate")) : null,
						args.has("dueDate"),
						textOrNull(args.path("dueAt")),
						args.has("dueAt"),
						longList(args.path("imageAssetIds")),
						args.has("imageAssetIds"));
				case "delete_task" -> userAssistantTaskService.cancelTaskJson(userId, args.path("taskId").asLong());
				case "list_growth_tasks" -> taskService.listForUserOnDateJson(
						userId, textOrNull(args.path("date")), textOrNull(args.path("status")));
				case "complete_growth_task" -> taskService.completeTaskJson(
						userId,
						args.path("taskId").asLong(),
						args.has("actualMinutes") && !args.path("actualMinutes").isNull()
								? args.path("actualMinutes").asInt()
								: null,
						args.has("qualityScore") && !args.path("qualityScore").isNull()
								? args.path("qualityScore").asInt()
								: null);
				case "propose_growth_plan" -> growthPlanProposalService.proposeFromToolJson(userId, args);
				default -> "{\"error\":\"未知工具: " + toolName + "\"}";
			};
			pipelineDebugLog.toolInvoke(debugPhase, round, toolName, argumentsJson, result);
			return result;
		} catch (Exception e) {
			String err = "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}";
			pipelineDebugLog.toolInvoke(debugPhase, round, toolName, argumentsJson, err);
			return err;
		}
	}

	private static String textOrNull(JsonNode node) {
		if (node == null || node.isMissingNode() || node.isNull()) {
			return null;
		}
		String s = node.asText();
		return s.isBlank() ? null : s;
	}

	private static List<Long> longList(JsonNode node) {
		if (node == null || node.isMissingNode() || !node.isArray()) {
			return List.of();
		}
		List<Long> ids = new ArrayList<>();
		node.forEach(n -> ids.add(n.asLong()));
		return ids;
	}

	private static String escapeJson(String s) {
		if (s == null) {
			return "";
		}
		return s.replace("\\", "\\\\").replace("\"", "\\\"");
	}
}
