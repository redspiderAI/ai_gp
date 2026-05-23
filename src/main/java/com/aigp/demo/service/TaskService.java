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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class TaskService {

	private final TaskRepository taskRepository;
	private final AppUserService appUserService;

	@Transactional(readOnly = true)
	public List<Task> listForUserOnDate(Long userId, LocalDate scheduledDate) {
		return taskRepository.findByUser_IdAndScheduledDateOrderByCreatedAtAsc(userId, scheduledDate);
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
