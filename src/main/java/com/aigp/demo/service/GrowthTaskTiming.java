package com.aigp.demo.service;

import com.aigp.demo.domain.task.Task;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/** 成长计划任务执行时刻计算（基于 started_at + estimated_minutes）。 */
public final class GrowthTaskTiming {

	private static final int MIN_EXECUTION_MINUTES = 1;

	private GrowthTaskTiming() {}

	/** 计划结束时刻 = 开始时刻 + 预估分钟（至少 1 分钟）。 */
	public static LocalDateTime plannedEndAt(Task task) {
		if (task.getStartedAt() == null) {
			return null;
		}
		int minutes = task.getEstimatedMinutes() != null && task.getEstimatedMinutes() > 0
				? task.getEstimatedMinutes()
				: MIN_EXECUTION_MINUTES;
		return task.getStartedAt().plusMinutes(minutes).truncatedTo(ChronoUnit.MINUTES);
	}

	public static int effectiveEstimatedMinutes(Task task) {
		return task.getEstimatedMinutes() != null && task.getEstimatedMinutes() > 0
				? task.getEstimatedMinutes()
				: MIN_EXECUTION_MINUTES;
	}
}
