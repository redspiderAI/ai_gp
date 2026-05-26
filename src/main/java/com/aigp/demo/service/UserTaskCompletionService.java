package com.aigp.demo.service;

import com.aigp.demo.domain.chat.UserAssistantTask;
import com.aigp.demo.domain.enums.UserTaskSource;
import com.aigp.demo.domain.task.Task;
import com.aigp.demo.web.growth.dto.GrowthTaskItemResponse;
import com.aigp.demo.web.user.dto.CompleteUserTaskResponse;
import com.aigp.demo.web.user.dto.UserAssistantTaskItemResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 统一将用户任务从未完成标记为已完成：{@code user_assistant_tasks}（OPEN→DONE）或 {@code tasks}（PENDING/IN_PROGRESS→COMPLETED）。
 * <p>对外入口：REST {@code POST /api/v1/users/me/tasks/complete}、AI 对话工具。
 */
@Service
@RequiredArgsConstructor
public class UserTaskCompletionService {

	private final UserAssistantTaskService userAssistantTaskService;
	private final TaskService taskService;
	private final MediaAssetService mediaAssetService;
	private final ObjectMapper objectMapper;

	@Transactional
	public CompleteUserTaskResponse complete(Long userId, UserTaskSource source, Long taskId) {
		if (taskId == null || taskId <= 0) {
			throw new IllegalArgumentException("taskId 须大于 0");
		}
		return switch (source) {
			case ASSISTANT -> CompleteUserTaskResponse.fromAssistant(completeAssistant(userId, taskId));
			case GROWTH -> CompleteUserTaskResponse.fromGrowth(completeGrowth(userId, taskId));
		};
	}

	@Transactional
	public CompleteUserTaskResponse complete(Long userId, String sourceRaw, Long taskId) {
		return complete(userId, UserTaskSource.parse(sourceRaw), taskId);
	}

	/** 供 AI 对话工具返回 JSON。 */
	public String completeToJson(Long userId, UserTaskSource source, Long taskId) {
		CompleteUserTaskResponse result = complete(userId, source, taskId);
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("ok", true);
		body.put("source", result.source());
		if (result.assistantTask() != null) {
			body.put("task", result.assistantTask());
		} else {
			body.put("task", result.growthTask());
		}
		try {
			return objectMapper.writeValueAsString(body);
		} catch (JsonProcessingException e) {
			return "{\"error\":\"序列化失败\"}";
		}
	}

	private UserAssistantTaskItemResponse completeAssistant(Long userId, Long taskId) {
		UserAssistantTask task = userAssistantTaskService.markComplete(userId, taskId);
		List<String> urls =
				mediaAssetService.findTaskImageUrls(List.of(task.getId())).getOrDefault(task.getId(), List.of());
		return UserAssistantTaskItemResponse.fromEntity(task, urls);
	}

	private GrowthTaskItemResponse completeGrowth(Long userId, Long taskId) {
		Task task = taskService.complete(userId, taskId, null, null);
		userAssistantTaskService.markDoneForLinkedGrowthPlanTask(
				userId, task.getTitle(), task.getScheduledDate());
		return GrowthTaskItemResponse.fromEntity(task);
	}
}
