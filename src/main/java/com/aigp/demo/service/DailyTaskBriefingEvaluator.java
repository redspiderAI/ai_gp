package com.aigp.demo.service;

import com.aigp.demo.domain.user.AppUser;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

/**
 * 判断用户本地是否处于「每日任务摘要」推送时刻（默认 08:00，与仅 due_date 提醒一致）。
 */
public final class DailyTaskBriefingEvaluator {

	private DailyTaskBriefingEvaluator() {}

	/**
	 * 当前分钟是否为用户本地的每日摘要时刻。
	 */
	public static boolean shouldDeliverBriefingNow(AppUser user, LocalTime briefingTime) {
		ZoneId zone = TaskReminderDueEvaluator.resolveZone(user.getTimezone());
		LocalDateTime nowMinute = LocalDateTime.now(zone).truncatedTo(ChronoUnit.MINUTES);
		LocalTime target = briefingTime == null ? LocalTime.of(8, 0) : briefingTime;
		LocalDateTime slot = LocalDateTime.of(nowMinute.toLocalDate(), target).truncatedTo(ChronoUnit.MINUTES);
		return nowMinute.equals(slot);
	}

	/**
	 * 今日是否已投递过每日摘要（按用户本地日历日）。
	 */
	public static boolean alreadySentToday(AppUser user, LocalDate lastSentDate) {
		if (lastSentDate == null) {
			return false;
		}
		ZoneId zone = TaskReminderDueEvaluator.resolveZone(user.getTimezone());
		return lastSentDate.equals(LocalDate.now(zone));
	}
}
