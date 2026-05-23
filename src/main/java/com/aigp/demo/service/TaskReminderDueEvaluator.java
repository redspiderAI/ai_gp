package com.aigp.demo.service;

import com.aigp.demo.domain.chat.UserAssistantTask;
import com.aigp.demo.domain.user.AppUser;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import org.springframework.util.StringUtils;

/**
 * 判断助手任务是否已在用户本地时区「到点」且尚未针对该次到期发送过提醒。
 */
public final class TaskReminderDueEvaluator {

	private TaskReminderDueEvaluator() {}

	/**
	 * @param defaultDateOnlyReminderTime 仅 {@code due_date}、无 {@code due_at} 时，在截止日当天该时刻提醒（如 08:00）
	 */
	public static boolean shouldSendNow(
			UserAssistantTask task, AppUser user, LocalTime defaultDateOnlyReminderTime) {
		ZoneId zone = resolveZone(user.getTimezone());
		LocalDateTime nowMinute = LocalDateTime.now(zone).truncatedTo(ChronoUnit.MINUTES);
		LocalDateTime dueMoment = resolveDueMoment(task, defaultDateOnlyReminderTime);
		if (dueMoment == null) {
			return false;
		}
		if (nowMinute.isBefore(dueMoment)) {
			return false;
		}
		return !alreadyRemindedForDue(task, dueMoment);
	}

	/** 计算本次到期的提醒时刻（用户本地，精确到分）。 */
	public static LocalDateTime resolveDueMoment(UserAssistantTask task, LocalTime defaultDateOnlyReminderTime) {
		if (task.getDueAt() != null) {
			return task.getDueAt().truncatedTo(ChronoUnit.MINUTES);
		}
		if (task.getDueDate() != null && defaultDateOnlyReminderTime != null) {
			return LocalDateTime.of(task.getDueDate(), defaultDateOnlyReminderTime);
		}
		return null;
	}

	static boolean alreadyRemindedForDue(UserAssistantTask task, LocalDateTime dueMoment) {
		if (task.getReminderSentAt() == null) {
			return false;
		}
		LocalDateTime sent = task.getReminderSentAt().truncatedTo(ChronoUnit.MINUTES);
		return !sent.isBefore(dueMoment);
	}

	public static ZoneId resolveZone(String timezone) {
		if (!StringUtils.hasText(timezone)) {
			return ZoneId.of("Asia/Shanghai");
		}
		try {
			return ZoneId.of(timezone.trim());
		} catch (Exception e) {
			return ZoneId.of("Asia/Shanghai");
		}
	}

	static LocalTime parseDefaultReminderTime(String raw, LocalTime fallback) {
		if (!StringUtils.hasText(raw)) {
			return fallback;
		}
		String t = raw.trim();
		try {
			if (t.length() >= 5) {
				return LocalTime.parse(t.substring(0, 5));
			}
			return LocalTime.parse(t);
		} catch (Exception e) {
			return fallback;
		}
	}
}
