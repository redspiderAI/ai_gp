package com.aigp.demo.service;

import com.aigp.demo.config.AppProperties;
import com.aigp.demo.domain.chat.AiChatMessage;
import com.aigp.demo.domain.chat.AiChatSession;
import com.aigp.demo.domain.chat.UserAssistantTask;
import com.aigp.demo.domain.enums.InAppNotificationType;
import com.aigp.demo.domain.enums.TaskStatus;
import com.aigp.demo.domain.enums.UserAssistantTaskStatus;
import com.aigp.demo.domain.task.Task;
import com.aigp.demo.domain.user.AppUser;
import com.aigp.demo.domain.user.UserCompanionMemory;
import com.aigp.demo.domain.user.UserNotificationSettings;
import com.aigp.demo.repository.TaskRepository;
import com.aigp.demo.repository.UserAssistantTaskRepository;
import com.aigp.demo.repository.UserCompanionMemoryRepository;
import com.aigp.demo.repository.UserNotificationSettingsRepository;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 每日任务摘要：用户本地默认 08:00 投递一句鼓励 + 当日待办；周六在同一条消息中附带本周回顾。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DailyTaskBriefingService {

	private static final LocalTime FALLBACK_BRIEFING_TIME = LocalTime.of(8, 0);
	private static final String GROWTH_PLAN_TASK_PREFIX = "[学习计划] ";
	private static final EnumSet<TaskStatus> GROWTH_TODAY_STATUSES =
			EnumSet.of(TaskStatus.PENDING, TaskStatus.IN_PROGRESS);
	/** 粗筛助手任务 due 窗口（天） */
	private static final int ASSISTANT_DUE_SCAN_DAYS = 2;

	private final AppProperties appProperties;
	private final UserAssistantTaskRepository userAssistantTaskRepository;
	private final TaskRepository taskRepository;
	private final UserCompanionMemoryRepository userCompanionMemoryRepository;
	private final UserNotificationSettingsRepository userNotificationSettingsRepository;
	private final UserNotificationSettingsService userNotificationSettingsService;
	private final AiChatReminderSessionService aiChatReminderSessionService;
	private final InAppNotificationService inAppNotificationService;

	/**
	 * 在已持有 task-reminder 锁的前提下调用（见 {@link AssistantTaskReminderService}）。
	 */
	@Transactional
	public int sendDueBriefings() {
		if (!appProperties.getTaskReminder().isEnabled()) {
			return 0;
		}
		if (!appProperties.getTaskReminder().isInAppEnabled()) {
			return 0;
		}
		LocalTime briefingTime = TaskReminderDueEvaluator.parseDefaultReminderTime(
				appProperties.getTaskReminder().getDefaultDueDateReminderTime(), FALLBACK_BRIEFING_TIME);

		Map<Long, BriefingCandidate> candidates = collectCandidates();
		int sent = 0;
		for (BriefingCandidate candidate : candidates.values()) {
			try {
				if (trySendBriefing(candidate, briefingTime)) {
					sent++;
				}
			} catch (Exception e) {
				log.warn("每日任务摘要失败 userId={}: {}", candidate.user.getId(), e.getMessage());
			}
		}
		if (sent > 0) {
			log.info("每日任务摘要已投递 {} 人", sent);
		}
		return sent;
	}

	/**
	 * 单任务到点提醒是否应并入每日摘要（避免 08:00 重复多条）。
	 */
	public boolean shouldDeferSingleReminder(UserAssistantTask task, AppUser user, LocalTime dateOnlyReminderTime) {
		if (!DailyTaskBriefingEvaluator.shouldDeliverBriefingNow(user, dateOnlyReminderTime)) {
			return false;
		}
		UserNotificationSettings settings = userNotificationSettingsService.getOrCreate(user);
		if (DailyTaskBriefingEvaluator.alreadySentToday(user, settings.getDailyBriefingLastSentDate())) {
			return true;
		}
		return isAssistantTaskDueToday(task, user);
	}

	private Map<Long, BriefingCandidate> collectCandidates() {
		Map<Long, BriefingCandidate> map = new HashMap<>();
		LocalDate utcToday = LocalDate.now(ZoneOffset.UTC);
		LocalDate from = utcToday.minusDays(ASSISTANT_DUE_SCAN_DAYS);
		LocalDate to = utcToday.plusDays(ASSISTANT_DUE_SCAN_DAYS);
		LocalDateTime dueAtFrom = utcToday.minusDays(ASSISTANT_DUE_SCAN_DAYS).atStartOfDay();
		LocalDateTime dueAtTo = utcToday.plusDays(ASSISTANT_DUE_SCAN_DAYS + 1L).atStartOfDay();

		List<UserAssistantTask> assistantRows = userAssistantTaskRepository.findOpenTasksWithDueDateBetween(
				UserAssistantTaskStatus.OPEN, from, to, dueAtFrom, dueAtTo);
		for (UserAssistantTask task : assistantRows) {
			map.computeIfAbsent(task.getUser().getId(), id -> new BriefingCandidate(task.getUser()))
					.assistantTasks
					.add(task);
		}

		List<Task> growthRows = taskRepository.findByStatusInAndScheduledDateBetween(
				GROWTH_TODAY_STATUSES, from, to);
		for (Task task : growthRows) {
			map.computeIfAbsent(task.getUser().getId(), id -> new BriefingCandidate(task.getUser()))
					.growthTasks
					.add(task);
		}

		for (UserCompanionMemory memory : userCompanionMemoryRepository.findPendingDigestDeliveries()) {
			map.computeIfAbsent(memory.getUser().getId(), id -> new BriefingCandidate(memory.getUser()))
					.companionMemory = memory;
		}
		return map;
	}

	private boolean trySendBriefing(BriefingCandidate candidate, LocalTime briefingTime) {
		AppUser user = candidate.user;
		if (user.getStatus() == null || user.getStatus() != 1) {
			return false;
		}
		if (!DailyTaskBriefingEvaluator.shouldDeliverBriefingNow(user, briefingTime)) {
			return false;
		}
		UserNotificationSettings settings = userNotificationSettingsService.getOrCreate(user);
		if (!settings.isDailyTaskReminder()) {
			return false;
		}
		if (DailyTaskBriefingEvaluator.alreadySentToday(user, settings.getDailyBriefingLastSentDate())) {
			return false;
		}

		ZoneId zone = TaskReminderDueEvaluator.resolveZone(user.getTimezone());
		LocalDate today = LocalDate.now(zone);

		List<UserAssistantTask> todayAssistant = filterAssistantTasksForToday(candidate.assistantTasks, today);
		List<Task> todayGrowth = filterGrowthTasksForToday(candidate.growthTasks, today);
		String weekDigest = resolveWeekDigestForBriefing(user, settings, candidate.companionMemory);

		if (todayAssistant.isEmpty() && todayGrowth.isEmpty() && !StringUtils.hasText(weekDigest)) {
			return false;
		}

		String body = buildBriefingBody(user, todayAssistant, todayGrowth, weekDigest);
		String title = buildBriefingTitle(today, weekDigest != null);

		deliverBriefing(user, title, body);
		markAssistantTasksReminded(todayAssistant, zone, briefingTime);
		markDigestDelivered(candidate.companionMemory, weekDigest);

		settings.setDailyBriefingLastSentDate(today);
		userNotificationSettingsRepository.save(settings);
		return true;
	}

	private List<UserAssistantTask> filterAssistantTasksForToday(List<UserAssistantTask> tasks, LocalDate today) {
		List<UserAssistantTask> out = new ArrayList<>();
		for (UserAssistantTask task : tasks) {
			if (isAssistantTaskDueOnDate(task, today)) {
				out.add(task);
			}
		}
		return out;
	}

	private List<Task> filterGrowthTasksForToday(List<Task> tasks, LocalDate today) {
		List<Task> out = new ArrayList<>();
		for (Task task : tasks) {
			if (today.equals(task.getScheduledDate())) {
				out.add(task);
			}
		}
		return out;
	}

	private static boolean isAssistantTaskDueToday(UserAssistantTask task, AppUser user) {
		ZoneId zone = TaskReminderDueEvaluator.resolveZone(user.getTimezone());
		return isAssistantTaskDueOnDate(task, LocalDate.now(zone));
	}

	private static boolean isAssistantTaskDueOnDate(UserAssistantTask task, LocalDate date) {
		if (task.getDueDate() != null && task.getDueDate().equals(date)) {
			return true;
		}
		return task.getDueAt() != null && task.getDueAt().toLocalDate().equals(date);
	}

	private String resolveWeekDigestForBriefing(
			AppUser user, UserNotificationSettings settings, UserCompanionMemory memory) {
		if (memory == null || !StringUtils.hasText(memory.getPendingDigestText())) {
			return null;
		}
		ZoneId zone = TaskReminderDueEvaluator.resolveZone(user.getTimezone());
		if (LocalDate.now(zone).getDayOfWeek() != DayOfWeek.SATURDAY) {
			return null;
		}
		if (!settings.isWeeklyCompanionDigest()) {
			return null;
		}
		if (!appProperties.getCompanionMemory().isEnabled()
				|| !appProperties.getCompanionMemory().isDigestInAppEnabled()) {
			return null;
		}
		String weekKey = memory.getSummarizedWeekKey();
		if (!StringUtils.hasText(weekKey) || weekKey.equals(memory.getDigestDeliveredWeekKey())) {
			return null;
		}
		return memory.getPendingDigestText().trim();
	}

	private static String buildBriefingTitle(LocalDate today, boolean hasWeekDigest) {
		if (hasWeekDigest) {
			return "早安 · 今日待办与本周回顾";
		}
		return "早安 · 今日待办";
	}

	private static String buildBriefingBody(
			AppUser user,
			List<UserAssistantTask> assistantTasks,
			List<Task> growthTasks,
			String weekDigest) {
		StringBuilder sb = new StringBuilder();
		sb.append(DailyEncouragementPhrases.pick(user)).append("\n\n");

		sb.append("【今日待办】\n");
		int index = 1;
		Set<String> listedTitles = new HashSet<>();
		for (Task growth : growthTasks) {
			sb.append(index++).append(". 📌 ").append(growth.getTitle()).append('\n');
			if (StringUtils.hasText(growth.getDescription())) {
				sb.append("   ").append(growth.getDescription().trim()).append('\n');
			}
			if (growth.getEstimatedMinutes() != null && growth.getEstimatedMinutes() > 0) {
				sb.append("   预计 ").append(growth.getEstimatedMinutes()).append(" 分钟\n");
			}
			listedTitles.add(growth.getTitle());
		}
		for (UserAssistantTask assistant : assistantTasks) {
			if (isDuplicateOfGrowthTask(assistant.getTitle(), listedTitles)) {
				continue;
			}
			sb.append(index++).append(". 📌 ").append(assistant.getTitle()).append('\n');
			if (StringUtils.hasText(assistant.getDescription())) {
				sb.append("   ").append(assistant.getDescription().trim()).append('\n');
			}
			String due = AssistantTaskDueParser.formatDueForDisplay(assistant);
			if (due != null) {
				sb.append("   提醒：").append(due).append('\n');
			}
		}
		if (index == 1) {
			sb.append("今日暂无排期任务，可以稍作休整或自由安排。\n");
		}

		if (StringUtils.hasText(weekDigest)) {
			sb.append("\n【本周回顾】\n").append(weekDigest).append('\n');
		}

		sb.append("\n在对话里告诉我「完成了」或「改天再做」，我可以帮你更新任务状态。");
		return sb.toString();
	}

	private void deliverBriefing(AppUser user, String title, String body) {
		String providerKey = resolveDefaultProviderKey();
		AppProperties.ChatProvider providerConfig = resolveProviderConfig(providerKey);
		AiChatSession session = aiChatReminderSessionService.getOrCreateReminderSession(
				user, providerKey, providerConfig.getModel());
		AiChatMessage message = aiChatReminderSessionService.appendAssistantMessage(session, body);
		inAppNotificationService.createAndPush(
				user,
				InAppNotificationType.TASK_DUE_REMINDER,
				title,
				truncate(body, 500),
				null,
				session,
				message.getId());
	}

	private void markAssistantTasksReminded(
			List<UserAssistantTask> tasks, ZoneId zone, LocalTime briefingTime) {
		LocalDateTime now = LocalDateTime.now(zone);
		for (UserAssistantTask task : tasks) {
			LocalDateTime dueMoment = TaskReminderDueEvaluator.resolveDueMoment(task, briefingTime);
			if (dueMoment == null) {
				continue;
			}
			if (task.getDueAt() == null || dueMoment.toLocalTime().equals(briefingTime)) {
				task.setReminderSentAt(now);
				userAssistantTaskRepository.save(task);
			}
		}
	}

	private void markDigestDelivered(UserCompanionMemory memory, String weekDigest) {
		if (memory == null || !StringUtils.hasText(weekDigest)) {
			return;
		}
		String weekKey = memory.getSummarizedWeekKey();
		if (StringUtils.hasText(weekKey)) {
			memory.setDigestDeliveredWeekKey(weekKey);
			userCompanionMemoryRepository.save(memory);
		}
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

	private static boolean isDuplicateOfGrowthTask(String assistantTitle, Set<String> growthTitles) {
		if (!StringUtils.hasText(assistantTitle)) {
			return false;
		}
		String title = assistantTitle.trim();
		if (growthTitles.contains(title)) {
			return true;
		}
		if (title.startsWith(GROWTH_PLAN_TASK_PREFIX)) {
			return growthTitles.contains(title.substring(GROWTH_PLAN_TASK_PREFIX.length()));
		}
		return false;
	}

	private static String truncate(String body, int max) {
		if (body == null) {
			return "";
		}
		String t = body.trim();
		return t.length() <= max ? t : t.substring(0, max);
	}

	private static final class BriefingCandidate {
		private final AppUser user;
		private final List<UserAssistantTask> assistantTasks = new ArrayList<>();
		private final List<Task> growthTasks = new ArrayList<>();
		private UserCompanionMemory companionMemory;

		private BriefingCandidate(AppUser user) {
			this.user = user;
		}
	}
}
