package com.aigp.demo.service;

import com.aigp.demo.config.AppProperties;
import com.aigp.demo.domain.chat.AiChatMessage;
import com.aigp.demo.domain.enums.ChatMessageRole;
import com.aigp.demo.domain.enums.InAppNotificationType;
import com.aigp.demo.domain.enums.UserAssistantTaskStatus;
import com.aigp.demo.domain.chat.UserAssistantTask;
import com.aigp.demo.domain.user.AppUser;
import com.aigp.demo.domain.user.UserCompanionMemory;
import com.aigp.demo.domain.user.UserNotificationSettings;
import com.aigp.demo.repository.AiChatMessageRepository;
import com.aigp.demo.repository.AppUserRepository;
import com.aigp.demo.repository.UserAssistantTaskRepository;
import com.aigp.demo.repository.UserCompanionMemoryRepository;
import com.aigp.demo.support.llm.ChatCompletionResult;
import com.aigp.demo.support.llm.OpenAiCompatibleChatClient;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 用户陪伴记忆：每周六凌晨「先总结本周、再合并长期记忆」；周六早上推送本周回顾（与 08:00 任务提醒同窗）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CompanionMemoryService {

	private static final DateTimeFormatter MSG_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

	private static final String WEEK_SUMMARY_SYSTEM =
			"""
			你是「本周回顾」内部模块。根据对话记录与任务统计，生成本周素材。
			不要编造未出现的信息；敏感内容用概括表述。
			必须严格按下列分隔符输出两段（不要 JSON）：

			===USER_DIGEST===
			（给用户看的本周回顾：温暖、第二人称、300～600 字，可分小段，可提待办与情绪，勿列技术字段）
			===END_USER_DIGEST===

			===WEEK_INTERNAL===
			（给系统合并长期记忆用：条目化列出本周事实、情绪、目标进展、重要约定；可较详细）
			===END_WEEK_INTERNAL===
			""";

	private static final String MERGE_MEMORY_SYSTEM =
			"""
			你是「长期陪伴记忆」管理员。根据「旧长期记忆」「本周内部总结」「用户画像」重写一份新的长期记忆全文（覆盖旧版，不是追加日志）。
			总字数不超过 %d 字。分区标题必须保留且按顺序输出：

			## 最近几周
			（最近约 4 周：可较详细，含具体事项与情绪）

			## 近一到两月
			（只保留里程碑、项目起止、重要关系与目标；例行会议不必逐条）

			## 更早
			（两个月以前：仅关键事件名 + 起止日期段，如「2026-01-10 至 2026-03-01 参加挑战杯」；不要会议逐条记录）

			【压缩规则】
			- 锻炼、学习、开会等例行活动：在「近一到两月」用汇总表述（如「上月锻炼约 8 次」「上周学习累计约 12 小时」），不要每次会议单独一行。
			- 合并重复、删除已过期且无用的细节。
			- 禁止编造；与旧记忆冲突时以本周总结为准。
			- 不要输出 JSON，只输出上述 Markdown 分区正文。
			""";

	private static final List<String> EXCLUDED_SESSION_TITLES = List.of(
			AiChatReminderSessionService.REMINDER_SESSION_TITLE,
			AiChatReminderSessionService.WEEKLY_DIGEST_SESSION_TITLE);

	private static final List<ChatMessageRole> CHAT_ROLES = List.of(ChatMessageRole.USER, ChatMessageRole.ASSISTANT);

	private final AppProperties appProperties;
	private final AppUserRepository appUserRepository;
	private final UserCompanionMemoryRepository userCompanionMemoryRepository;
	private final AiChatMessageRepository aiChatMessageRepository;
	private final UserAssistantTaskRepository userAssistantTaskRepository;
	private final UserNotificationSettingsService userNotificationSettingsService;
	private final ChatProviderResolver chatProviderResolver;
	private final OpenAiCompatibleChatClient openAiCompatibleChatClient;
	private final AiChatReminderSessionService aiChatReminderSessionService;
	private final InAppNotificationService inAppNotificationService;
	private final SchedulerLockService schedulerLockService;

	/**
	 * 供对话 system 注入的长期记忆正文；无记录时返回 empty。
	 */
	@Transactional(readOnly = true)
	public Optional<String> getMemoryTextForChat(Long userId) {
		return userCompanionMemoryRepository
				.findByUser_Id(userId)
				.map(UserCompanionMemory::getMemoryText)
				.filter(StringUtils::hasText);
	}

	/**
	 * 每周六凌晨批量：先本周总结，再合并长期记忆，并写入待推送回顾。
	 *
	 * @return 成功总结的用户数
	 */
	@Transactional
	public int runWeeklySummarizeBatch() {
		if (!appProperties.getCompanionMemory().isEnabled()) {
			return 0;
		}
		if (!schedulerLockService.tryAcquireCompanionWeeklyLock(Duration.ofMinutes(55))) {
			return 0;
		}
		ZoneId storageZone = ZoneId.of(appProperties.getCompanionMemory().getZone());
		ZonedDateTime batchEnd = ZonedDateTime.now(storageZone);
		ZonedDateTime batchStart = batchEnd.minusDays(7);
		// 与 Hibernate jdbc.time_zone 一致：库内 DATETIME 即北京时间
		LocalDateTime fromLocal = batchStart.toLocalDateTime();
		LocalDateTime toLocal = batchEnd.toLocalDateTime();

		List<Long> userIds = aiChatMessageRepository.findDistinctUserIdsWithMessagesBetween(
				fromLocal, toLocal, CHAT_ROLES, EXCLUDED_SESSION_TITLES);
		int ok = 0;
		for (Long userId : userIds) {
			try {
				if (summarizeOneUser(userId, storageZone, fromLocal, toLocal)) {
					ok++;
				}
			} catch (Exception e) {
				log.warn("用户周总结失败 userId={}: {}", userId, e.getMessage());
			}
		}
		if (ok > 0) {
			log.info("陪伴记忆周总结完成 {} 人", ok);
		}
		return ok;
	}

	/**
	 * 每分钟调用：在用户本地周六 {@code digestDeliveryTime} 推送待发送的本周回顾。
	 */
	@Transactional
	public int deliverPendingDigests() {
		if (!appProperties.getCompanionMemory().isEnabled()) {
			return 0;
		}
		if (!appProperties.getCompanionMemory().isDigestInAppEnabled()) {
			return 0;
		}
		LocalTime deliveryTime = TaskReminderDueEvaluator.parseDefaultReminderTime(
				appProperties.getCompanionMemory().getDigestDeliveryTime(), LocalTime.of(8, 0));
		List<UserCompanionMemory> pending = userCompanionMemoryRepository.findPendingDigestDeliveries();
		int sent = 0;
		for (UserCompanionMemory memory : pending) {
			try {
				if (tryDeliverDigest(memory, deliveryTime)) {
					sent++;
				}
			} catch (Exception e) {
				log.warn("本周回顾推送失败 userId={}: {}", memory.getUserId(), e.getMessage());
			}
		}
		if (sent > 0) {
			log.info("本周陪伴回顾已推送 {} 人", sent);
		}
		return sent;
	}

	private boolean summarizeOneUser(Long userId, ZoneId storageZone, LocalDateTime fromLocal, LocalDateTime toLocal) {
		AppUser user = appUserRepository.findById(userId).orElse(null);
		if (user == null || user.getStatus() == null || user.getStatus() != 1) {
			return false;
		}
		ZoneId zone = TaskReminderDueEvaluator.resolveZone(user.getTimezone());
		String weekKey = CompanionMemoryWeekKeys.currentWeekKey(zone);

		UserCompanionMemory memory = userCompanionMemoryRepository
				.findByUser_Id(userId)
				.orElseGet(() -> createMemoryRow(user));

		if (weekKey.equals(memory.getSummarizedWeekKey())) {
			return false;
		}

		List<AiChatMessage> messages = aiChatMessageRepository.findUserMessagesBetween(
				userId, fromLocal, toLocal, CHAT_ROLES, EXCLUDED_SESSION_TITLES);
		int maxMsgs = Math.max(10, appProperties.getCompanionMemory().getMaxMessagesPerWeek());
		if (messages.size() > maxMsgs) {
			messages = messages.subList(messages.size() - maxMsgs, messages.size());
		}
		if (messages.isEmpty()) {
			return false;
		}

		String transcript = buildTranscript(messages, storageZone, zone);
		String taskStats = buildWeekTaskStats(userId, fromLocal, toLocal);
		AppProperties.ChatProvider provider = chatProviderResolver.resolvePlatformForBackgroundJob();

		WeekSummaryParts parts = callWeekSummaryLlm(provider, user, weekKey, transcript, taskStats);
		if (parts == null || !StringUtils.hasText(parts.weekInternal())) {
			return false;
		}

		String merged = callMergeMemoryLlm(provider, user, memory.getMemoryText(), parts.weekInternal());
		if (!StringUtils.hasText(merged)) {
			return false;
		}

		memory.setMemoryText(truncate(merged, appProperties.getCompanionMemory().getMaxMemoryChars()));
		memory.setWeekInternalSummary(truncate(parts.weekInternal(), 8000));
		memory.setPendingDigestText(truncate(parts.userDigest(), 4000));
		memory.setSummarizedWeekKey(weekKey);
		memory.setLastSummarizedAt(LocalDateTime.now(storageZone));
		userCompanionMemoryRepository.save(memory);
		return true;
	}

	private UserCompanionMemory createMemoryRow(AppUser user) {
		UserCompanionMemory row = new UserCompanionMemory();
		row.setUser(user);
		return userCompanionMemoryRepository.save(row);
	}

	private boolean tryDeliverDigest(UserCompanionMemory memory, LocalTime deliveryTime) {
		AppUser user = memory.getUser();
		if (user.getStatus() == null || user.getStatus() != 1) {
			return false;
		}
		if (!CompanionDigestDeliveryEvaluator.shouldDeliverNow(user, deliveryTime)) {
			return false;
		}
		UserNotificationSettings notificationSettings = userNotificationSettingsService.getOrCreate(user);
		// 周六 08:00 已并入每日任务摘要时不再单独推送「本周回顾」会话
		if (DailyTaskBriefingEvaluator.alreadySentToday(user, notificationSettings.getDailyBriefingLastSentDate())) {
			return false;
		}
		if (!StringUtils.hasText(memory.getPendingDigestText())) {
			return false;
		}
		String weekKey = memory.getSummarizedWeekKey();
		if (!StringUtils.hasText(weekKey)) {
			return false;
		}
		if (weekKey.equals(memory.getDigestDeliveredWeekKey())) {
			return false;
		}

		String providerKey = defaultProviderKey();
		AppProperties.ChatProvider providerConfig = chatProviderResolver.resolvePlatformForBackgroundJob();
		String body = memory.getPendingDigestText().trim();
		AiChatReminderSessionService.NoticeDelivery delivery =
				aiChatReminderSessionService.appendAssistantNoticeToLatestSession(
						user, providerKey, providerConfig.getModel(), body);
		String title = "本周回顾 · " + weekKey;

		inAppNotificationService.createAndPush(
				user,
				InAppNotificationType.WEEKLY_COMPANION_DIGEST,
				title,
				truncate(body, 500),
				null,
				delivery.session(),
				delivery.message().getId());

		memory.setDigestDeliveredWeekKey(weekKey);
		userCompanionMemoryRepository.save(memory);
		return true;
	}

	private WeekSummaryParts callWeekSummaryLlm(
			AppProperties.ChatProvider provider,
			AppUser user,
			String weekKey,
			String transcript,
			String taskStats) {
		StringBuilder userContent = new StringBuilder();
		userContent.append("周键：").append(weekKey).append('\n');
		userContent.append("用户昵称：").append(nullToDash(user.getNickname())).append('\n');
		userContent.append("\n【本周任务统计】\n").append(taskStats).append('\n');
		userContent.append("\n【本周对话记录】\n").append(transcript);

		List<Map<String, Object>> messages = List.of(
				Map.of("role", "system", "content", WEEK_SUMMARY_SYSTEM),
				Map.of("role", "user", "content", userContent.toString()));

		try {
			ChatCompletionResult result =
					openAiCompatibleChatClient.chat(provider, messages, null, "companion-week");
			return parseWeekSummary(result.content());
		} catch (Exception e) {
			log.warn("本周总结 LLM 失败 userId={}: {}", user.getId(), e.getMessage());
			return null;
		}
	}

	private String callMergeMemoryLlm(
			AppProperties.ChatProvider provider,
			AppUser user,
			String oldMemory,
			String weekInternal) {
		int maxChars = Math.max(2000, appProperties.getCompanionMemory().getMaxMemoryChars());
		String system = String.format(Locale.ROOT, MERGE_MEMORY_SYSTEM, maxChars);
		StringBuilder userContent = new StringBuilder();
		userContent.append("【用户画像】\n");
		userContent.append("- 昵称：").append(nullToDash(user.getNickname())).append('\n');
		userContent.append("- 身份：").append(nullToDash(user.getProfileIdentity())).append('\n');
		userContent.append("- 爱好：").append(nullToDash(user.getProfileHobbies())).append('\n');
		userContent.append("- 探索方向：").append(nullToDash(user.getProfileExploration())).append('\n');
		userContent.append("\n【旧长期记忆】\n");
		userContent.append(StringUtils.hasText(oldMemory) ? oldMemory.trim() : "（尚无）");
		userContent.append("\n\n【本周内部总结】\n").append(weekInternal.trim());

		List<Map<String, Object>> messages = List.of(
				Map.of("role", "system", "content", system),
				Map.of("role", "user", "content", userContent.toString()));

		try {
			ChatCompletionResult result =
					openAiCompatibleChatClient.chat(provider, messages, null, "companion-merge");
			return result.content();
		} catch (Exception e) {
			log.warn("合并长期记忆 LLM 失败 userId={}: {}", user.getId(), e.getMessage());
			return null;
		}
	}

	private static WeekSummaryParts parseWeekSummary(String raw) {
		if (!StringUtils.hasText(raw)) {
			return null;
		}
		String userDigest = extractBetween(raw, "===USER_DIGEST===", "===END_USER_DIGEST===");
		String weekInternal = extractBetween(raw, "===WEEK_INTERNAL===", "===END_WEEK_INTERNAL===");
		if (!StringUtils.hasText(weekInternal)) {
			weekInternal = raw.trim();
		}
		if (!StringUtils.hasText(userDigest)) {
			userDigest = weekInternal.length() > 600 ? weekInternal.substring(0, 600) + "…" : weekInternal;
		}
		return new WeekSummaryParts(userDigest.trim(), weekInternal.trim());
	}

	private static String extractBetween(String text, String startMarker, String endMarker) {
		int start = text.indexOf(startMarker);
		if (start < 0) {
			return null;
		}
		start += startMarker.length();
		int end = text.indexOf(endMarker, start);
		if (end < 0) {
			return text.substring(start).trim();
		}
		return text.substring(start, end).trim();
	}

	private static String buildTranscript(List<AiChatMessage> messages, ZoneId storageZone, ZoneId displayZone) {
		StringBuilder sb = new StringBuilder();
		for (AiChatMessage m : messages) {
			String role = m.getRole() == ChatMessageRole.USER ? "用户" : "助手";
			String time = m.getCreatedAt() == null
					? ""
					: m.getCreatedAt().atZone(storageZone).withZoneSameInstant(displayZone).format(MSG_TIME);
			String content = m.getContent() == null ? "" : m.getContent().trim();
			if (content.length() > 1200) {
				content = content.substring(0, 1200) + "…";
			}
			sb.append('[').append(time).append("] ").append(role).append("：").append(content).append("\n\n");
		}
		return sb.toString();
	}

	private String buildWeekTaskStats(Long userId, LocalDateTime fromLocal, LocalDateTime toLocal) {
		List<UserAssistantTask> tasks =
				userAssistantTaskRepository.findByUser_IdAndUpdatedAtBetween(userId, fromLocal, toLocal);
		if (tasks.isEmpty()) {
			return "（本周无任务变更记录）";
		}
		int created = 0;
		int done = 0;
		int cancelled = 0;
		StringBuilder highlights = new StringBuilder();
		for (UserAssistantTask t : tasks) {
			if (t.getStatus() == UserAssistantTaskStatus.DONE) {
				done++;
			} else if (t.getStatus() == UserAssistantTaskStatus.CANCELLED) {
				cancelled++;
			} else {
				created++;
			}
			if (highlights.length() < 1500 && StringUtils.hasText(t.getTitle())) {
				highlights
						.append("- ")
						.append(t.getTitle())
						.append("（")
						.append(t.getStatus().name())
						.append("）\n");
			}
		}
		return "新建/进行中相关 " + created + " 条，完成 " + done + " 条，取消 " + cancelled + " 条。\n" + highlights;
	}

	private String defaultProviderKey() {
		String def = appProperties.getChat().getDefaultProvider();
		return StringUtils.hasText(def) ? def.trim().toLowerCase(Locale.ROOT) : "mimo";
	}

	private static String nullToDash(String s) {
		return StringUtils.hasText(s) ? s.trim() : "—";
	}

	private static String truncate(String s, int max) {
		if (s == null) {
			return null;
		}
		String t = s.trim();
		return t.length() <= max ? t : t.substring(0, max);
	}

	private record WeekSummaryParts(String userDigest, String weekInternal) {}
}
