package com.aigp.demo.service;

import com.aigp.demo.domain.chat.UserAssistantTask;
import com.aigp.demo.domain.enums.UserAssistantTaskStatus;
import com.aigp.demo.domain.user.AppUser;
import com.aigp.demo.exception.ConflictException;
import com.aigp.demo.exception.NotFoundException;
import com.aigp.demo.repository.UserAssistantTaskRepository;
import com.aigp.demo.web.user.dto.UserAssistantTaskItemResponse;
import com.aigp.demo.web.user.dto.UserAssistantTaskListResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class UserAssistantTaskService {

	private final UserAssistantTaskRepository userAssistantTaskRepository;
	private final AppUserService appUserService;
	private final MediaAssetService mediaAssetService;
	private final ObjectMapper objectMapper;
	private final AssistantTaskNearDueScheduleService assistantTaskNearDueScheduleService;

	/**
	 * 查询当前用户的助手任务清单；{@code status} 为空时返回全部状态。
	 */
	@Transactional(readOnly = true)
	public UserAssistantTaskListResponse listTasksForUser(Long userId, String status) {
		appUserService.requireActive(userId);
		List<UserAssistantTask> list;
		if (StringUtils.hasText(status)) {
			UserAssistantTaskStatus st = parseStatus(status);
			list = userAssistantTaskRepository.findByUser_IdAndStatusOrderByUpdatedAtDesc(userId, st);
		} else {
			list = userAssistantTaskRepository.findByUser_IdOrderByUpdatedAtDesc(userId);
		}
		List<Long> taskIds = list.stream().map(UserAssistantTask::getId).toList();
		Map<Long, List<String>> imageUrlsByTask = mediaAssetService.findTaskImageUrls(taskIds);
		List<UserAssistantTaskItemResponse> items = list.stream()
				.map(t -> UserAssistantTaskItemResponse.fromEntity(
						t, imageUrlsByTask.getOrDefault(t.getId(), List.of())))
				.toList();
		return new UserAssistantTaskListResponse(items.size(), items);
	}

	/**
	 * 查询用户在指定自然日到期的助手待办（{@code due_date} 或 {@code due_at} 落在该日），含全部状态。
	 */
	@Transactional(readOnly = true)
	public List<UserAssistantTaskItemResponse> listForUserOnDate(Long userId, LocalDate date) {
		appUserService.requireActive(userId);
		LocalDateTime dayStart = date.atStartOfDay();
		LocalDateTime dayEnd = date.plusDays(1).atStartOfDay();
		List<UserAssistantTask> list =
				userAssistantTaskRepository.findByUser_IdAndDueOnDate(userId, date, dayStart, dayEnd);
		List<Long> taskIds = list.stream().map(UserAssistantTask::getId).toList();
		Map<Long, List<String>> imageUrlsByTask = mediaAssetService.findTaskImageUrls(taskIds);
		return list.stream()
				.map(t -> UserAssistantTaskItemResponse.fromEntity(
						t, imageUrlsByTask.getOrDefault(t.getId(), List.of())))
				.toList();
	}

	/** 供对话 system 注入：助手任务简要列表（最多 30 条）；{@code statusFilter} 为空表示全部状态 */
	@Transactional(readOnly = true)
	public String buildTasksSummaryForChat(Long userId, String statusFilter, String userMessage) {
		AppUser user = appUserService.requireActive(userId);
		ZoneId zone = resolveZone(user.getTimezone());
		AssistantTaskDateFilter dateFilter = AssistantTaskDateFilter.inferFromUserMessage(userMessage, zone);
		return buildTasksSummaryForChat(userId, statusFilter, dateFilter);
	}

	@Transactional(readOnly = true)
	public String buildTasksSummaryForChat(Long userId, String statusFilter, AssistantTaskDateFilter dateFilter) {
		List<UserAssistantTask> list = loadTasks(userId, statusFilter);
		list = applyDateFilter(list, dateFilter);
		if (list.isEmpty()) {
			return null;
		}
		int max = Math.min(30, list.size());
		StringBuilder sb = new StringBuilder();
		if (dateFilter != null) {
			sb.append("（已按日期筛选：");
			if (dateFilter.dueFromInclusive() != null) {
				sb.append("从 ").append(dateFilter.dueFromInclusive());
			}
			if (dateFilter.dueToInclusive() != null) {
				sb.append(" 至 ").append(dateFilter.dueToInclusive());
			}
			sb.append("）\n");
		}
		for (int i = 0; i < max; i++) {
			UserAssistantTask t = list.get(i);
			sb.append("- [id=")
					.append(t.getId())
					.append("] ")
					.append(t.getTitle());
			String due = AssistantTaskDueParser.formatDueForDisplay(t);
			if (due != null) {
				sb.append("（截止 ").append(due).append('）');
			}
			sb.append('\n');
		}
		if (list.size() > max) {
			sb.append("… 另有 ").append(list.size() - max).append(" 条，请用 list_tasks 查看");
		}
		return sb.toString();
	}

	@Transactional(readOnly = true)
	public String listTasksJson(Long userId, String statusFilter, String dueFrom, String dueTo) {
		AppUser user = appUserService.requireActive(userId);
		ZoneId zone = resolveZone(user.getTimezone());
		AssistantTaskDateFilter dateFilter = AssistantTaskDateFilter.merge(
				AssistantTaskDateFilter.fromArgs(dueFrom, dueTo),
				AssistantTaskDateFilter.inferFromUserMessage(AiChatRequestContext.getUserMessage(), zone));
		List<UserAssistantTask> list = applyDateFilter(loadTasks(userId, statusFilter), dateFilter);
		List<Map<String, Object>> rows = new ArrayList<>();
		for (UserAssistantTask t : list) {
			rows.add(toCompactMap(t));
		}
		return toJson(Map.of("tasks", rows, "count", rows.size()));
	}

	@Transactional(readOnly = true)
	public String getTaskJson(Long userId, Long taskId) {
		UserAssistantTask t = requireOwned(userId, taskId);
		return toJson(toMap(t));
	}

	@Transactional
	public String createTaskJson(
			Long userId, String title, String description, String dueDate, String dueAt, List<Long> imageAssetIds) {
		if (!StringUtils.hasText(title)) {
			throw new IllegalArgumentException("任务标题不能为空");
		}
		AppUser user = appUserService.requireActive(userId);
		UserAssistantTask task = new UserAssistantTask();
		task.setUser(user);
		task.setTitle(title.trim());
		if (StringUtils.hasText(description)) {
			task.setDescription(description.trim());
		}
		AssistantTaskDueParser.applyDue(
				task, dueDate, dueAt, StringUtils.hasText(dueDate), StringUtils.hasText(dueAt));
		AssistantTaskDueParser.normalizeDue(task);
		task.setStatus(UserAssistantTaskStatus.OPEN);
		userAssistantTaskRepository.save(task);
		if (imageAssetIds != null && !imageAssetIds.isEmpty()) {
			mediaAssetService.requireOwned(userId, imageAssetIds);
			mediaAssetService.replaceTaskImages(task.getId(), imageAssetIds);
		}
		assistantTaskNearDueScheduleService.registerAfterCommit(task.getId());
		return toJson(Map.of("ok", true, "task", toMap(task)));
	}

	@Transactional
	public String updateTaskJson(
			Long userId,
			Long taskId,
			String title,
			String description,
			String status,
			String dueDate,
			boolean dueDatePresent,
			String dueAt,
			boolean dueAtPresent,
			List<Long> imageAssetIds,
			boolean imageAssetIdsPresent) {
		UserAssistantTask task = requireOwned(userId, taskId);
		if (title != null) {
			if (!StringUtils.hasText(title)) {
				throw new IllegalArgumentException("任务标题不能为空");
			}
			task.setTitle(title.trim());
		}
		if (description != null) {
			task.setDescription(StringUtils.hasText(description) ? description.trim() : null);
		}
		if (status != null && StringUtils.hasText(status)) {
			UserAssistantTaskStatus next = parseStatus(status.trim().toUpperCase());
			if (next == UserAssistantTaskStatus.DONE) {
				task = markComplete(userId, taskId);
			} else {
				task.setStatus(next);
			}
		}
		if (dueDatePresent || dueAtPresent) {
			AssistantTaskDueParser.applyDue(task, dueDate, dueAt, dueDatePresent, dueAtPresent);
			AssistantTaskDueParser.normalizeDue(task);
			task.setReminderSentAt(null);
		}
		userAssistantTaskRepository.save(task);
		if (imageAssetIdsPresent) {
			if (imageAssetIds != null && !imageAssetIds.isEmpty()) {
				mediaAssetService.requireOwned(userId, imageAssetIds);
				mediaAssetService.replaceTaskImages(task.getId(), imageAssetIds);
			} else {
				mediaAssetService.replaceTaskImages(task.getId(), List.of());
			}
		}
		if (task.getStatus() == UserAssistantTaskStatus.CANCELLED || task.getStatus() == UserAssistantTaskStatus.DONE) {
			assistantTaskNearDueScheduleService.cancel(task.getId());
		} else if (dueDatePresent || dueAtPresent) {
			assistantTaskNearDueScheduleService.registerAfterCommit(task.getId());
		}
		return toJson(Map.of("ok", true, "task", toMap(task)));
	}

	@Transactional
	public UserAssistantTask markComplete(Long userId, Long taskId) {
		UserAssistantTask task = requireOwned(userId, taskId);
		if (task.getStatus() != UserAssistantTaskStatus.OPEN) {
			throw new ConflictException("仅未完成（OPEN）可标记完成，当前: " + task.getStatus());
		}
		task.setStatus(UserAssistantTaskStatus.DONE);
		userAssistantTaskRepository.save(task);
		assistantTaskNearDueScheduleService.cancel(task.getId());
		return task;
	}

	@Transactional
	public String cancelTaskJson(Long userId, Long taskId) {
		UserAssistantTask task = requireOwned(userId, taskId);
		task.setStatus(UserAssistantTaskStatus.CANCELLED);
		userAssistantTaskRepository.save(task);
		assistantTaskNearDueScheduleService.cancel(task.getId());
		return toJson(Map.of("ok", true, "task", toMap(task)));
	}

	/**
	 * 成长计划任务标记完成后，同步将同日标题为「[学习计划] {title}」的助手待办置为 DONE（若存在）。
	 */
	@Transactional
	public void markDoneForLinkedGrowthPlanTask(Long userId, String growthTaskTitle, LocalDate scheduledDate) {
		if (!StringUtils.hasText(growthTaskTitle) || scheduledDate == null) {
			return;
		}
		String linkedTitle = "[学习计划] " + growthTaskTitle.trim();
		List<UserAssistantTask> openOnDate = userAssistantTaskRepository.findByUser_IdAndDueOnDate(
				userId, scheduledDate, scheduledDate.atStartOfDay(), scheduledDate.plusDays(1).atStartOfDay());
		for (UserAssistantTask task : openOnDate) {
			if (task.getStatus() != UserAssistantTaskStatus.OPEN) {
				continue;
			}
			if (linkedTitle.equals(task.getTitle())) {
				task.setStatus(UserAssistantTaskStatus.DONE);
				userAssistantTaskRepository.save(task);
				assistantTaskNearDueScheduleService.cancel(task.getId());
				return;
			}
		}
	}

	private UserAssistantTask requireOwned(Long userId, Long taskId) {
		return userAssistantTaskRepository
				.findByIdAndUser_Id(taskId, userId)
				.orElseThrow(() -> new NotFoundException("任务不存在: id=" + taskId));
	}

	private static UserAssistantTaskStatus parseStatus(String status) {
		try {
			return UserAssistantTaskStatus.valueOf(status.trim().toUpperCase());
		} catch (IllegalArgumentException e) {
			throw new IllegalArgumentException("status 须为 OPEN、DONE 或 CANCELLED");
		}
	}

	private List<UserAssistantTask> loadTasks(Long userId, String statusFilter) {
		if (StringUtils.hasText(statusFilter)) {
			return userAssistantTaskRepository.findByUser_IdAndStatusOrderByUpdatedAtDesc(
					userId, parseStatus(statusFilter));
		}
		return userAssistantTaskRepository.findByUser_IdOrderByUpdatedAtDesc(userId);
	}

	private static List<UserAssistantTask> applyDateFilter(List<UserAssistantTask> list, AssistantTaskDateFilter filter) {
		if (filter == null || list == null || list.isEmpty()) {
			return list;
		}
		return list.stream()
				.filter(t -> filter.matches(t.getDueDate() != null ? t.getDueDate() : dueDateOf(t)))
				.toList();
	}

	private static ZoneId resolveZone(String timezone) {
		if (!StringUtils.hasText(timezone)) {
			return ZoneId.of("Asia/Shanghai");
		}
		try {
			return ZoneId.of(timezone.trim());
		} catch (Exception e) {
			return ZoneId.of("Asia/Shanghai");
		}
	}

	private static LocalDate dueDateOf(UserAssistantTask t) {
		return t.getDueAt() != null ? t.getDueAt().toLocalDate() : null;
	}

	/** 供 AI 工具 list_tasks 使用：省略长 description，降低 execute 第二轮 prompt 体积。 */
	private Map<String, Object> toCompactMap(UserAssistantTask t) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("id", t.getId());
		m.put("title", t.getTitle());
		m.put("status", t.getStatus().name());
		m.put("dueDate", t.getDueDate() == null ? null : t.getDueDate().toString());
		m.put("dueAt", AssistantTaskDueParser.formatDueAt(t.getDueAt()));
		return m;
	}

	private Map<String, Object> toMap(UserAssistantTask t) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("id", t.getId());
		m.put("title", t.getTitle());
		m.put("description", t.getDescription());
		m.put("status", t.getStatus().name());
		m.put("dueDate", t.getDueDate() == null ? null : t.getDueDate().toString());
		m.put("dueAt", AssistantTaskDueParser.formatDueAt(t.getDueAt()));
		List<Long> imageIds = mediaAssetService.findTaskAssetIds(t.getId());
		m.put("imageAssetIds", imageIds);
		m.put("imageUrls", mediaAssetService.buildPublicUrls(imageIds));
		m.put("createdAt", t.getCreatedAt() == null ? null : t.getCreatedAt().toString());
		m.put("updatedAt", t.getUpdatedAt() == null ? null : t.getUpdatedAt().toString());
		return m;
	}

	private String toJson(Object value) {
		try {
			return objectMapper.writeValueAsString(value);
		} catch (JsonProcessingException e) {
			return "{\"error\":\"序列化失败\"}";
		}
	}
}
