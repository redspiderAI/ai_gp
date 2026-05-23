package com.aigp.demo.service;

import com.aigp.demo.domain.enums.TaskStatus;
import com.aigp.demo.domain.goal.Goal;
import com.aigp.demo.domain.goal.Milestone;
import com.aigp.demo.domain.plan.Plan;
import com.aigp.demo.domain.task.Task;
import com.aigp.demo.domain.user.AppUser;
import com.aigp.demo.exception.ConflictException;
import com.aigp.demo.exception.NotFoundException;
import com.aigp.demo.repository.TaskRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
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
public class TaskService {

	private final TaskRepository taskRepository;
	private final AppUserService appUserService;
	private final UserAssistantTaskService userAssistantTaskService;
	private final ObjectMapper objectMapper;

	@Transactional(readOnly = true)
	public List<Task> listForUserOnDate(Long userId, LocalDate scheduledDate) {
		return taskRepository.findByUser_IdAndScheduledDateOrderByCreatedAtAsc(userId, scheduledDate);
	}

	/** 供 AI 对话工具：按用户本地自然日查询成长计划任务（JSON）。 */
	@Transactional(readOnly = true)
	public String listForUserOnDateJson(Long userId, String date, String statusFilter) {
		AppUser user = appUserService.requireActive(userId);
		LocalDate scheduledDate = resolveQueryDate(user, date);
		List<Task> tasks = listForUserOnDate(userId, scheduledDate);
		if (StringUtils.hasText(statusFilter)) {
			TaskStatus st = parseGrowthStatus(statusFilter);
			tasks = tasks.stream().filter(t -> t.getStatus() == st).toList();
		}
		List<Map<String, Object>> rows = new ArrayList<>();
		for (Task t : tasks) {
			rows.add(toChatMap(t));
		}
		return toJson(Map.of(
				"ok", true,
				"date", scheduledDate.toString(),
				"tasks", rows,
				"count", rows.size()));
	}

	/** 供 AI 对话工具：标记成长计划任务已完成（等同 HTTP complete 接口规则）。 */
	@Transactional
	public String completeTaskJson(Long userId, Long taskId, Integer actualMinutes, Integer qualityScore) {
		Task task = complete(userId, taskId, actualMinutes, qualityScore);
		userAssistantTaskService.markDoneForLinkedGrowthPlanTask(
				userId, task.getTitle(), task.getScheduledDate());
		return toJson(Map.of("ok", true, "task", toChatMap(task)));
	}

	/** 供 AI system 注入：今日成长计划任务摘要。 */
	@Transactional(readOnly = true)
	public String buildGrowthTasksSummaryForChat(Long userId, LocalDate date) {
		List<Task> tasks = listForUserOnDate(userId, date);
		if (tasks.isEmpty()) {
			return null;
		}
		StringBuilder sb = new StringBuilder();
		sb.append("（计划日 ").append(date).append("）\n");
		for (Task t : tasks) {
			sb.append("- [id=")
					.append(t.getId())
					.append("] ")
					.append(t.getTitle())
					.append("（")
					.append(t.getStatus().name())
					.append("）");
			if (t.getEstimatedMinutes() != null && t.getEstimatedMinutes() > 0) {
				sb.append("，预计 ").append(t.getEstimatedMinutes()).append(" 分钟");
			}
			sb.append('\n');
		}
		return sb.toString();
	}

	private LocalDate resolveQueryDate(AppUser user, String date) {
		if (StringUtils.hasText(date)) {
			try {
				return LocalDate.parse(date.trim());
			} catch (DateTimeParseException e) {
				throw new IllegalArgumentException("date 格式须为 yyyy-MM-dd");
			}
		}
		ZoneId zone = TaskReminderDueEvaluator.resolveZone(user.getTimezone());
		return LocalDate.now(zone);
	}

	private static TaskStatus parseGrowthStatus(String raw) {
		try {
			return TaskStatus.valueOf(raw.trim().toUpperCase());
		} catch (IllegalArgumentException e) {
			throw new IllegalArgumentException("status 须为 PENDING、IN_PROGRESS、COMPLETED 等成长任务状态");
		}
	}

	private Map<String, Object> toChatMap(Task t) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("id", t.getId());
		m.put("title", t.getTitle());
		m.put("description", t.getDescription());
		m.put("scheduledDate", t.getScheduledDate() == null ? null : t.getScheduledDate().toString());
		m.put("status", t.getStatus().name());
		m.put("estimatedMinutes", t.getEstimatedMinutes());
		m.put("startedAt", t.getStartedAt() == null ? null : t.getStartedAt().toString());
		m.put("completedAt", t.getCompletedAt() == null ? null : t.getCompletedAt().toString());
		m.put("actualMinutes", t.getActualMinutes());
		return m;
	}

	private String toJson(Object value) {
		try {
			return objectMapper.writeValueAsString(value);
		} catch (JsonProcessingException e) {
			return "{\"error\":\"序列化失败\"}";
		}
	}

	/**
	 * 开始执行成长计划任务：仅在该任务的计划日（scheduledDate，用户时区自然日）且 PENDING 时可调用。
	 * 计划日当天 0 点起即可开始，无需等到每日提醒时刻；不可早于或晚于该自然日。
	 */
	@Transactional
	public Task startExecution(Long userId, Long taskId) {
		Task task = requireOwned(userId, taskId);
		if (task.getStatus() != TaskStatus.PENDING) {
			throw new ConflictException("仅待执行状态可开始，当前: " + task.getStatus());
		}
		AppUser user = appUserService.requireActive(userId);
		ZoneId zone = TaskReminderDueEvaluator.resolveZone(user.getTimezone());
		if (!GrowthTaskScheduleRules.isOnPlanDay(task, zone)) {
			LocalDate planDay = task.getScheduledDate();
			LocalDate today = LocalDate.now(zone);
			if (today.isBefore(planDay)) {
				throw new ConflictException("未到计划日，须在 " + planDay + " 当天开始");
			}
			throw new ConflictException("计划日 " + planDay + " 已过，须在计划日当天开始");
		}
		LocalDateTime now = LocalDateTime.now(zone).truncatedTo(ChronoUnit.MINUTES);
		task.setStartedAt(now);
		task.setStatus(TaskStatus.IN_PROGRESS);
		return taskRepository.save(task);
	}

	/**
	 * 提前完成（或计划日当天直接完成）：仅 {@code PENDING} / {@code IN_PROGRESS}，且须在计划日当天。
	 */
	@Transactional
	public Task complete(Long userId, Long taskId, Integer actualMinutes, Integer qualityScore) {
		Task task = requireOwned(userId, taskId);
		if (task.getStatus() != TaskStatus.PENDING && task.getStatus() != TaskStatus.IN_PROGRESS) {
			throw new ConflictException("仅待执行或进行中可标记完成");
		}
		AppUser user = appUserService.requireActive(userId);
		ZoneId zone = TaskReminderDueEvaluator.resolveZone(user.getTimezone());
		if (!GrowthTaskScheduleRules.isOnPlanDay(task, zone)) {
			LocalDate planDay = task.getScheduledDate();
			LocalDate today = LocalDate.now(zone);
			if (today.isBefore(planDay)) {
				throw new ConflictException("未到计划日，须在 " + planDay + " 当天完成");
			}
			throw new ConflictException("计划日 " + planDay + " 已过，无法标记完成");
		}
		task.setStatus(TaskStatus.COMPLETED);
		task.setCompletedAt(LocalDateTime.now(zone).truncatedTo(ChronoUnit.MINUTES));
		if (actualMinutes != null) {
			task.setActualMinutes(actualMinutes);
		}
		if (qualityScore != null) {
			task.setQualityScore(qualityScore.byteValue());
		}
		return task;
	}

	@Transactional
	public Task skip(Long userId, Long taskId, String skipReason) {
		if (!StringUtils.hasText(skipReason)) {
			throw new IllegalArgumentException("skipReason is required");
		}
		Task task = requireOwned(userId, taskId);
		if (task.getStatus() != TaskStatus.PENDING) {
			throw new ConflictException("仅待执行状态可跳过");
		}
		task.setStatus(TaskStatus.SKIPPED);
		task.setSkipReason(skipReason);
		return task;
	}

	/** Creates a task row under a plan; caller must ensure plan is active and goal/user match. */
	@Transactional
	public Task scheduleTask(
			Long userId,
			Plan plan,
			Goal goal,
			Milestone milestone,
			String title,
			String description,
			LocalDate scheduledDate,
			int estimatedMinutes) {
		if (!goal.getUser().getId().equals(userId)) {
			throw new IllegalArgumentException("goal does not belong to user");
		}
		if (!plan.getGoal().getId().equals(goal.getId())) {
			throw new IllegalArgumentException("plan does not belong to goal");
		}
		Task task = new Task();
		task.setPlan(plan);
		task.setGoal(goal);
		task.setUser(goal.getUser());
		task.setMilestone(milestone);
		task.setTitle(title);
		task.setDescription(description);
		task.setScheduledDate(scheduledDate);
		task.setEstimatedMinutes(estimatedMinutes);
		task.setStatus(TaskStatus.PENDING);
		return taskRepository.save(task);
	}

	private Task requireOwned(Long userId, Long taskId) {
		return taskRepository
				.findByIdAndUser_Id(taskId, userId)
				.orElseThrow(() -> new NotFoundException("成长计划任务不存在: id=" + taskId));
	}
}
