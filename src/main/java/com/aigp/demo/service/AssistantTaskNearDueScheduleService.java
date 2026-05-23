package com.aigp.demo.service;

import com.aigp.demo.config.AppProperties;
import com.aigp.demo.domain.chat.UserAssistantTask;
import com.aigp.demo.domain.enums.UserAssistantTaskStatus;
import com.aigp.demo.domain.user.AppUser;
import com.aigp.demo.repository.UserAssistantTaskRepository;
import com.aigp.demo.support.schedule.AssistantTaskReminderDebugLog;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 为「近期到点」的助手任务注册一次性调度，避免仅依赖整分钟 cron 而漏发（如「3 分钟后提醒我」）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssistantTaskNearDueScheduleService {

	private static final LocalTime FALLBACK_DATE_ONLY_TIME = LocalTime.of(8, 0);
	/** 仅对接下来 48 小时内到期的任务注册一次性调度 */
	private static final int NEAR_DUE_HOURS = 48;
	private static final int RESCHEDULE_FLOOR_HOURS = 36;

	private final TaskScheduler taskScheduler;
	private final AppProperties appProperties;
	private final UserAssistantTaskRepository userAssistantTaskRepository;
	private final AssistantTaskReminderService assistantTaskReminderService;
	private final AssistantTaskReminderDebugLog reminderDebugLog;

	private final Map<Long, ScheduledFuture<?>> scheduledByTaskId = new ConcurrentHashMap<>();

	/** 事务提交后再注册，确保 taskId 已落库。 */
	public void registerAfterCommit(Long taskId) {
		if (taskId == null || taskId <= 0) {
			return;
		}
		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
				@Override
				public void afterCommit() {
					reschedule(taskId);
				}
			});
		} else {
			reschedule(taskId);
		}
	}

	public void cancel(Long taskId) {
		if (taskId == null) {
			return;
		}
		ScheduledFuture<?> future = scheduledByTaskId.remove(taskId);
		if (future != null) {
			future.cancel(false);
			reminderDebugLog.schedule("cancel", taskId, "-", "one-shot cancelled");
		}
	}

	/** 应用启动后为 48 小时内到期的 OPEN 任务补注册一次性调度（服务重启后不丢近期提醒）。 */
	public void rescheduleAllOpenNearDue() {
		if (!appProperties.getTaskReminder().isEnabled() || !appProperties.getTaskReminder().isInAppEnabled()) {
			return;
		}
		ZoneId scanZone = ZoneId.of(appProperties.getTaskReminder().getZone());
		LocalDateTime dueAtFloor =
				LocalDateTime.now(scanZone).minusHours(RESCHEDULE_FLOOR_HOURS).truncatedTo(ChronoUnit.MINUTES);
		LocalDateTime dueAtCeiling =
				LocalDateTime.now(scanZone).plusHours(NEAR_DUE_HOURS).truncatedTo(ChronoUnit.MINUTES);
		LocalDate dueDateCeiling = LocalDate.now(scanZone).plusDays(2);
		List<UserAssistantTask> candidates = userAssistantTaskRepository.findOpenTasksReminderCandidates(
				UserAssistantTaskStatus.OPEN, dueAtFloor, dueAtCeiling, dueDateCeiling);
		int registered = 0;
		for (UserAssistantTask task : candidates) {
			if (task.getDueAt() != null) {
				reschedule(task.getId());
				registered++;
			}
		}
		if (registered > 0) {
			log.info("启动后为 {} 条近期 due_at 任务注册到点调度", registered);
		}
	}

	public void reschedule(Long taskId) {
		cancel(taskId);
		if (!appProperties.getTaskReminder().isEnabled() || !appProperties.getTaskReminder().isInAppEnabled()) {
			reminderDebugLog.schedule("skip", taskId, "-", "reminder_disabled");
			return;
		}
		UserAssistantTask task = userAssistantTaskRepository.findByIdWithUser(taskId).orElse(null);
		if (task == null) {
			reminderDebugLog.schedule("skip", taskId, "-", "task_not_found");
			return;
		}
		if (task.getStatus() != UserAssistantTaskStatus.OPEN) {
			reminderDebugLog.schedule(
					"skip", taskId, "-", "userId=" + task.getUser().getId() + " status=" + task.getStatus());
			return;
		}
		AppUser user = task.getUser();
		LocalTime dateOnlyTime = TaskReminderDueEvaluator.parseDefaultReminderTime(
				appProperties.getTaskReminder().getDefaultDueDateReminderTime(), FALLBACK_DATE_ONLY_TIME);
		LocalDateTime dueMoment = TaskReminderDueEvaluator.resolveDueMoment(task, dateOnlyTime);
		if (dueMoment == null) {
			reminderDebugLog.schedule("skip", taskId, "-", "userId=" + user.getId() + " no_due_moment");
			return;
		}
		ZoneId zone = TaskReminderDueEvaluator.resolveZone(user.getTimezone());
		Instant now = Instant.now();
		Instant fireAt = dueMoment.atZone(zone).toInstant();
		Instant horizon = now.plus(NEAR_DUE_HOURS, ChronoUnit.HOURS);
		if (fireAt.isAfter(horizon)) {
			reminderDebugLog.schedule(
					"skip",
					taskId,
					"-",
					"userId="
							+ user.getId()
							+ " beyond_horizon due="
							+ dueMoment
							+ " horizonHours="
							+ NEAR_DUE_HOURS);
			return;
		}
		// 已过期：2 秒后补发（与分钟 cron 双保险）
		boolean overdueCatchUp = !dueMoment.atZone(zone).toInstant().isAfter(now);
		if (overdueCatchUp) {
			fireAt = now.plusSeconds(2);
		}
		ScheduledFuture<?> future =
				taskScheduler.schedule(() -> fireReminder(taskId), fireAt);
		scheduledByTaskId.put(taskId, future);
		reminderDebugLog.schedule(
				"register",
				taskId,
				fireAt.toString(),
				"due=" + dueMoment + " zone=" + zone.getId() + (overdueCatchUp ? " overdue_catch_up" : ""));
	}

	private void fireReminder(Long taskId) {
		scheduledByTaskId.remove(taskId);
		reminderDebugLog.schedule("fire", taskId, Instant.now().toString(), "one-shot triggered");
		try {
			if (assistantTaskReminderService.sendReminderForTaskId(taskId)) {
				log.info("近期到点调度已投递提醒 taskId={}", taskId);
			}
		} catch (Exception e) {
			log.warn("近期到点调度失败 taskId={}: {}", taskId, e.getMessage());
			reminderDebugLog.schedule("error", taskId, "-", e.getMessage());
		}
	}
}
