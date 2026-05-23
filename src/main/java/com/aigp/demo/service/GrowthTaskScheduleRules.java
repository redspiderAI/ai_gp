package com.aigp.demo.service;

import com.aigp.demo.domain.task.Task;
import java.time.LocalDate;
import java.time.ZoneId;

/** 成长计划任务（tasks）计划日相关规则。 */
public final class GrowthTaskScheduleRules {

	private GrowthTaskScheduleRules() {}

	/** 当前用户本地日历日是否为该任务的计划日（scheduledDate）。 */
	public static boolean isOnPlanDay(Task task, ZoneId zone) {
		return LocalDate.now(zone).equals(task.getScheduledDate());
	}

	/** 计划日是否已过去（用户本地日历已进入次日及以后）。 */
	public static boolean isPlanDayPast(Task task, ZoneId zone) {
		return task.getScheduledDate().isBefore(LocalDate.now(zone));
	}
}
