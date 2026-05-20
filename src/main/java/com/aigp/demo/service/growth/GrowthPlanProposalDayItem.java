package com.aigp.demo.service.growth;

import java.time.LocalDate;

/** 计划草案中的单日学习任务（确认后写入 tasks 表并同步每日提醒待办）。 */
public record GrowthPlanProposalDayItem(
		int dayIndex, LocalDate scheduledDate, String title, String description, int estimatedMinutes) {}
