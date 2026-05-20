package com.aigp.demo.web.growth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "确认计划草案后的结果")
public record GrowthPlanProposalConfirmResponse(
		@Schema(description = "草案 ID") Long proposalId,
		@Schema(description = "新建目标 ID") Long goalId,
		@Schema(description = "新建计划版本 ID") Long planId,
		@Schema(description = "写入成长计划 tasks 表条数") int growthTaskCount,
		@Schema(description = "同步创建的助手提醒待办条数") int reminderTaskCount,
		@Schema(description = "每日提醒时刻 HH:mm") String dailyReminderTime,
		@Schema(description = "给用户的说明文案") String message) {}
