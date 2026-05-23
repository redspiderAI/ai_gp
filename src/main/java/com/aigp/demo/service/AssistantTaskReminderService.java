package com.aigp.demo.service;

import com.aigp.demo.config.AppProperties;
import com.aigp.demo.domain.chat.AiChatMessage;
import com.aigp.demo.domain.chat.AiChatSession;
import com.aigp.demo.domain.chat.UserAssistantTask;
import com.aigp.demo.domain.enums.InAppNotificationType;
import com.aigp.demo.domain.enums.UserAssistantTaskStatus;
import com.aigp.demo.domain.user.AppUser;
import com.aigp.demo.domain.user.UserNotificationSettings;
import com.aigp.demo.repository.UserAssistantTaskRepository;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
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
	/** 粗筛窗口：覆盖全球时区与略迟触发的 tick */
	private static final int SCAN_FLOOR_HOURS = 36;
	private static final int SCAN_CEILING_HOURS = 14;

	private final AppProperties appProperties;
	private final UserAssistantTaskRepository userAssistantTaskRepository;
	private final UserNotificationSettingsService userNotificationSettingsService;
	private final AiChatReminderSessionService aiChatReminderSessionService;
	private final InAppNotificationService inAppNotificationService;
	private final SchedulerLockService schedulerLockService;
	private final DailyTaskBriefingService dailyTaskBriefingService;

	/**
	 * 由定时任务每分钟调用：获取分布式锁后扫描并发送到期提醒。
	 */
	@Transactional
	public int sendDueReminders() {
		if (!appProperties.getTaskReminder().isEnabled()) {
			return 0;
		}
		if (!appProperties.getTaskReminder().isInAppEnabled()) {
			return 0;
		}
		if (!schedulerLockService.tryAcquireTaskReminderLock(Duration.ofSeconds(50))) {
			return 0;
		}
		dailyTaskBriefingService.sendDueBriefings();
		LocalDateTime dueAtFloor =
				LocalDateTime.now(ZoneOffset.UTC).minusHours(SCAN_FLOOR_HOURS).truncatedTo(ChronoUnit.MINUTES);
		LocalDateTime dueAtCeiling =
				LocalDateTime.now(ZoneOffset.UTC).plusHours(SCAN_CEILING_HOURS).truncatedTo(ChronoUnit.MINUTES);
		LocalDate dueDateCeiling = LocalDate.now(ZoneOffset.UTC).plusDays(2);
		List<UserAssistantTask> candidates = userAssistantTaskRepository.findOpenTasksReminderCandidates(
				UserAssistantTaskStatus.OPEN, dueAtFloor, dueAtCeiling, dueDateCeiling);
		LocalTime dateOnlyTime = TaskReminderDueEvaluator.parseDefaultReminderTime(
				appProperties.getTaskReminder().getDefaultDueDateReminderTime(), FALLBACK_DATE_ONLY_TIME);
		int sent = 0;
		for (UserAssistantTask task : candidates) {
			if (trySendReminder(task, dateOnlyTime)) {
				sent++;
			}
		}
		if (sent > 0) {
			log.info("助手任务到点提醒已投递 {} 条", sent);
		}
		return sent;
	}

	private boolean trySendReminder(UserAssistantTask task, LocalTime dateOnlyReminderTime) {
		AppUser user = task.getUser();
		if (user.getStatus() == null || user.getStatus() != 1) {
			return false;
		}
		if (!TaskReminderDueEvaluator.shouldSendNow(task, user, dateOnlyReminderTime)) {
			return false;
		}
		if (dailyTaskBriefingService.shouldDeferSingleReminder(task, user, dateOnlyReminderTime)) {
			return false;
		}
		UserNotificationSettings settings = userNotificationSettingsService.getOrCreate(user);
		if (!settings.isDailyTaskReminder()) {
			return false;
		}

		LocalDateTime dueMoment = TaskReminderDueEvaluator.resolveDueMoment(task, dateOnlyReminderTime);
		deliverInAppReminder(user, task, dueMoment);
		task.setReminderSentAt(
				LocalDateTime.now(TaskReminderDueEvaluator.resolveZone(user.getTimezone())));
		userAssistantTaskRepository.save(task);
		return true;
	}

	private void deliverInAppReminder(AppUser user, UserAssistantTask task, LocalDateTime dueMoment) {
		String providerKey = resolveDefaultProviderKey();
		AppProperties.ChatProvider providerConfig = resolveProviderConfig(providerKey);
		AiChatSession session = aiChatReminderSessionService.getOrCreateReminderSession(
				user, providerKey, providerConfig.getModel());
		String chatBody = buildChatReminderBody(task, dueMoment);
		AiChatMessage message = aiChatReminderSessionService.appendAssistantMessage(session, chatBody);
		String title = buildTitle(task, dueMoment);
		inAppNotificationService.createAndPush(
				user,
				InAppNotificationType.TASK_DUE_REMINDER,
				title,
				truncateForNotification(chatBody),
				task.getId(),
				session,
				message.getId());
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
