package com.aigp.demo.service;

import com.aigp.demo.config.AppProperties;
import com.aigp.demo.domain.enums.TaskStatus;
import com.aigp.demo.domain.task.Task;
import com.aigp.demo.domain.user.AppUser;
import com.aigp.demo.repository.TaskRepository;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 成长计划任务执行态：到时自动完成、计划日跨日标为未完成（与助手每日提醒独立）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GrowthTaskExecutionService {

	private static final EnumSet<TaskStatus> TICK_STATUSES =
			EnumSet.of(TaskStatus.PENDING, TaskStatus.IN_PROGRESS);

	private final AppProperties appProperties;
	private final TaskRepository taskRepository;
	private final SchedulerLockService schedulerLockService;

	/**
	 * 每分钟与助手提醒同窗调用：进行中任务到时完成；昨日及更早仍为 PENDING 的标未完成。
	 */
	@Transactional
	public int processMinuteTick() {
		if (!appProperties.getGrowthTask().isExecutionEnabled()) {
			return 0;
		}
		if (!schedulerLockService.tryAcquireGrowthTaskExecutionLock(Duration.ofSeconds(50))) {
			return 0;
		}
		List<Task> candidates = taskRepository.findByStatusIn(TICK_STATUSES);
		int changed = 0;
		for (Task task : candidates) {
			AppUser user = task.getUser();
			if (user.getStatus() == null || user.getStatus() != 1) {
				continue;
			}
			ZoneId zone = TaskReminderDueEvaluator.resolveZone(user.getTimezone());
			LocalDateTime nowMinute = LocalDateTime.now(zone).truncatedTo(ChronoUnit.MINUTES);

			if (task.getStatus() == TaskStatus.IN_PROGRESS) {
				if (tryAutoComplete(task, nowMinute)) {
					changed++;
				} else if (GrowthTaskScheduleRules.isPlanDayPast(task, zone)) {
					task.setStatus(TaskStatus.INCOMPLETE);
					taskRepository.save(task);
					changed++;
				}
				continue;
			}
			if (task.getStatus() == TaskStatus.PENDING && GrowthTaskScheduleRules.isPlanDayPast(task, zone)) {
				task.setStatus(TaskStatus.INCOMPLETE);
				taskRepository.save(task);
				changed++;
			}
		}
		if (changed > 0) {
			log.info("成长计划任务状态批处理更新 {} 条", changed);
		}
		return changed;
	}

	private boolean tryAutoComplete(Task task, LocalDateTime nowMinute) {
		LocalDateTime endAt = GrowthTaskTiming.plannedEndAt(task);
		if (endAt == null || nowMinute.isBefore(endAt)) {
			return false;
		}
		task.setStatus(TaskStatus.COMPLETED);
		task.setCompletedAt(nowMinute);
		taskRepository.save(task);
		return true;
	}
}
