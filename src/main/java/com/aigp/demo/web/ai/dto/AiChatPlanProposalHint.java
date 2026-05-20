package com.aigp.demo.web.ai.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 本轮对话若 AI 已提交成长计划草案，前端用 proposalId 拉详情并展示「确认 / 拒绝」。
 */
@Schema(description = "待用户确认的成长计划草案摘要")
public record AiChatPlanProposalHint(
		@Schema(description = "草案 ID，用于 GET/confirm/reject 接口") Long proposalId,
		@Schema(description = "PENDING") String status,
		@Schema(description = "目标标题") String goalTitle,
		@Schema(description = "计划总述") String summary,
		@Schema(description = "计划天数") int dayCount,
		@Schema(description = "首日 yyyy-MM-dd") String startDate,
		@Schema(description = "末日 yyyy-MM-dd") String endDate,
		@Schema(description = "每日提醒 HH:mm") String dailyReminderTime) {}
