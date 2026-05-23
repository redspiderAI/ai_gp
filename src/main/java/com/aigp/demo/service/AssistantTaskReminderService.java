package com.aigp.demo.service;

import com.aigp.demo.config.AppProperties;
import com.aigp.demo.domain.chat.UserAssistantTask;
import com.aigp.demo.domain.enums.InAppNotificationType;
import com.aigp.demo.domain.enums.UserAssistantTaskStatus;
import com.aigp.demo.domain.user.AppUser;
import com.aigp.demo.repository.UserAssistantTaskRepository;
import com.aigp.demo.support.schedule.AssistantTaskReminderDebugLog;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 助手任务到点提醒：每分钟扫描候选任务，按用户本地 {@code due_at}（或仅 {@code due_date} 的默认时刻）投递。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssistantTaskReminderService {

	private static final LocalTime FALLBACK_DATE_ONLY_TIME = LocalTime.of(8, 0);
	/** 近期 due_at 粗筛下限（小时）；更早的仅当 {@code reminder_sent_at} 为空时纳入补发 */
	private static final int SCAN_FLOOR_HOURS = 36;
	private static final int SCAN_CEILING_HOURS = 48;
	/** 单次 tick 最多投递条数，防止启动补发时短时间刷屏 */
	private static final int MAX_REMINDERS_PER_TICK = 30;

	private final AppProperties appProperties;
	private final UserAssistantTaskRepository userAssistantTaskRepository;
	private final AiChatReminderSessionService aiChatReminderSessionService;
	private final InAppNotificationService inAppNotificationService;
	private final SchedulerLockService schedulerLockService;
	private final DailyTaskBriefingService dailyTaskBriefingService;
	private final AssistantTaskReminderDebugLog reminderDebugLog;

	private static final String SOURCE_CRON = "cron";
	private static final String SOURCE_NEAR_DUE = "near_due";

	/**
	 * 由定时任务每分钟调用：获取分布式锁后扫描并发送到期提醒。
	 */
	@Transactional
	public int sendDueReminders() {
		return sendDueReminders(SOURCE_CRON);
	}

	@Transactional
	public int sendDueReminders(String source) {
		if (!appProperties.getTaskReminder().isEnabled()) {
			reminderDebugLog.line("CONFIG", "source=%s skipped: task-reminder.enabled=false", source);
			return 0;
		}
		if (!appProperties.getTaskReminder().isInAppEnabled()) {
			reminderDebugLog.line("CONFIG", "source=%s skipped: task-reminder.in-app-enabled=false", source);
			return 0;
		}
		if (!schedulerLockService.tryAcquireTaskReminderLock(Duration.ofSeconds(50))) {
			log.debug("助手任务提醒 tick 未获得调度锁，跳过本分钟");
			reminderDebugLog.line("LOCK", "source=%s skipped: scheduler_lock not acquired", source);
			return 0;
		}
		try {
			dailyTaskBriefingService.sendDueBriefings();
		} catch (Exception e) {
			log.warn("每日任务摘要失败，继续单任务提醒: {}", e.getMessage());
		}
		ZoneId scanZone = ZoneId.of(appProperties.getTaskReminder().getZone());
		LocalDateTime dueAtFloor =
				LocalDateTime.now(scanZone).minusHours(SCAN_FLOOR_HOURS).truncatedTo(ChronoUnit.MINUTES);
		LocalDateTime dueAtCeiling =
				LocalDateTime.now(scanZone).plusHours(SCAN_CEILING_HOURS).truncatedTo(ChronoUnit.MINUTES);
		LocalDate dueDateCeiling = LocalDate.now(scanZone).plusDays(2);
		List<UserAssistantTask> candidates = userAssistantTaskRepository.findOpenTasksReminderCandidates(
				UserAssistantTaskStatus.OPEN, dueAtFloor, dueAtCeiling, dueDateCeiling);
		String scanWindow = dueAtFloor + " ~ " + dueAtCeiling + " dueDate<=" + dueDateCeiling;
		reminderDebugLog.tickBegin(source, candidates.size(), scanWindow);
		LocalTime dateOnlyTime = TaskReminderDueEvaluator.parseDefaultReminderTime(
				appProperties.getTaskReminder().getDefaultDueDateReminderTime(), FALLBACK_DATE_ONLY_TIME);
		int sent = 0;
		for (UserAssistantTask task : candidates) {
			if (sent >= MAX_REMINDERS_PER_TICK) {
				log.info("助手任务提醒已达本 tick 上限 {} 条，其余候选下一分钟继续", MAX_REMINDERS_PER_TICK);
				reminderDebugLog.line("LIMIT", "source=%s hit max=%d", source, MAX_REMINDERS_PER_TICK);
				break;
			}
			if (trySendReminder(task, dateOnlyTime, source)) {
				sent++;
			}
		}
		if (sent > 0) {
			log.info("助手任务到点提醒已投递 {} 条（候选 {} 条）", sent, candidates.size());
		}
		reminderDebugLog.tickEnd(source, sent, candidates.size());
		return sent;
	}

	/**
	 * 按任务 id 尝试投递一次提醒（近期到点调度 / 手动补发）；独立事务。
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public boolean sendReminderForTaskId(Long taskId) {
		if (!appProperties.getTaskReminder().isEnabled() || !appProperties.getTaskReminder().isInAppEnabled()) {
			reminderDebugLog.skip(SOURCE_NEAR_DUE, taskId, null, "reminder_disabled");
			return false;
		}
		UserAssistantTask task = userAssistantTaskRepository.findByIdWithUser(taskId).orElse(null);
		if (task == null) {
			reminderDebugLog.skip(SOURCE_NEAR_DUE, taskId, null, "task_not_found");
			return false;
		}
		if (task.getStatus() != UserAssistantTaskStatus.OPEN) {
			reminderDebugLog.skip(
					SOURCE_NEAR_DUE, taskId, task.getUser().getId(), "status=" + task.getStatus());
			return false;
		}
		LocalTime dateOnlyTime = TaskReminderDueEvaluator.parseDefaultReminderTime(
				appProperties.getTaskReminder().getDefaultDueDateReminderTime(), FALLBACK_DATE_ONLY_TIME);
		return trySendReminder(task, dateOnlyTime, SOURCE_NEAR_DUE);
	}

	private boolean trySendReminder(UserAssistantTask task, LocalTime dateOnlyReminderTime, String source) {
		AppUser user = task.getUser();
		String skip = TaskReminderDueEvaluator.explainSkipReason(task, user, dateOnlyReminderTime);
		if (skip != null) {
			reminderDebugLog.skip(source, task.getId(), user.getId(), skip);
			return false;
		}
		if (dailyTaskBriefingService.shouldDeferSingleReminder(task, user, dateOnlyReminderTime)) {
			reminderDebugLog.skip(source, task.getId(), user.getId(), "deferred_by_daily_briefing");
			return false;
		}

		LocalDateTime dueMoment = TaskReminderDueEvaluator.resolveDueMoment(task, dateOnlyReminderTime);
		AiChatReminderSessionService.NoticeDelivery delivery =
				deliverInAppReminder(user, task, dueMoment);
		task.setReminderSentAt(
				LocalDateTime.now(TaskReminderDueEvaluator.resolveZone(user.getTimezone())));
		userAssistantTaskRepository.save(task);
		log.info(
				"助手任务提醒已投递 taskId={} userId={} sessionTarget=latest due={}",
				task.getId(),
				user.getId(),
				AssistantTaskDueParser.formatDueForDisplay(task));
		reminderDebugLog.sent(
				source,
				task.getId(),
				user.getId(),
				delivery.session().getId(),
				delivery.message().getId(),
				AssistantTaskDueParser.formatDueForDisplay(task),
				task.getReminderSentAt().toString());
		return true;
	}

	private AiChatReminderSessionService.NoticeDelivery deliverInAppReminder(
			AppUser user, UserAssistantTask task, LocalDateTime dueMoment) {
		String providerKey = resolveDefaultProviderKey();
		AppProperties.ChatProvider providerConfig = resolveProviderConfig(providerKey);
		String chatBody = buildChatReminderBody(task, dueMoment);
		AiChatReminderSessionService.NoticeDelivery delivery =
				aiChatReminderSessionService.appendAssistantNoticeToLatestSession(
						user, providerKey, providerConfig.getModel(), chatBody);
		String title = buildTitle(task, dueMoment);
		inAppNotificationService.createAndPush(
				user,
				InAppNotificationType.TASK_DUE_REMINDER,
				title,
				truncateForNotification(chatBody),
				task.getId(),
				delivery.session(),
				delivery.message().getId());
		inAppNotificationService.pushReminderChatMessage(
				user.getId(), delivery.session().getId(), delivery.message().getId(), chatBody);
		return delivery;
	}

	private String resolveDefaultProviderKey() {
		String def = appProperties.getChat().getDefaultProvider();
		return StringUtils.hasText(def) ? def.trim().toLowerCase(Locale.ROOT) : "mimo";
	}

	private AppProperties.ChatProvider resolveProviderConfig(String providerKey) {
		AppProperties.ChatProvider cfg = appProperties.getChat().getProviders().get(providerKey);
		if (cfg == null || !StringUtils.hasText(cfg.getModel())) {
			cfg = new AppProperties.ChatProvider();
			cfg.setModel("system");
		}
		return cfg;
	}

	private static String buildTitle(UserAssistantTask task, LocalDateTime dueMoment) {
		LocalDate today = dueMoment.toLocalDate();
		if (task.getDueDate() != null && task.getDueDate().isBefore(today)) {
			return "任务已逾期：" + task.getTitle();
		}
		if (task.getDueAt() != null) {
			return "提醒：" + task.getTitle();
		}
		return "今日待办：" + task.getTitle();
	}

	private static String buildChatReminderBody(UserAssistantTask task, LocalDateTime dueMoment) {
		String due = AssistantTaskDueParser.formatDueForDisplay(task);
		if (due == null) {
			due = dueMoment.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
		}
		String line;
		if (task.getDueAt() != null) {
			line = "已到提醒时间（" + due + "）。";
		} else if (task.getDueDate() != null && task.getDueDate().isBefore(dueMoment.toLocalDate())) {
			line = "你有一项任务原定于 " + due + " 完成，目前已逾期。";
		} else {
			line = "今天（" + due + "）有一项待办需要你关注。";
		}
		StringBuilder sb = new StringBuilder();
		sb.append(line).append("\n\n");
		sb.append("📌 ").append(task.getTitle()).append('\n');
		if (StringUtils.hasText(task.getDescription())) {
			sb.append(task.getDescription().trim()).append('\n');
		}
		sb.append("\n在对话里告诉我「完成了」或「改天再做」，我可以帮你更新任务状态。");
		return sb.toString();
	}

	private static String truncateForNotification(String body) {
		if (body == null) {
			return "";
		}
		String t = body.trim();
		return t.length() <= 500 ? t : t.substring(0, 500);
	}
}
