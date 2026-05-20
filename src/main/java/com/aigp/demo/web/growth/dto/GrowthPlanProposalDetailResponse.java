package com.aigp.demo.web.growth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Schema(description = "成长计划草案详情（待确认 / 已处理）")
public record GrowthPlanProposalDetailResponse(
		@Schema(description = "草案 ID") Long proposalId,
		@Schema(description = "PENDING / CONFIRMED / REJECTED") String status,
		@Schema(description = "来源对话会话 ID，可为空") Long sessionId,
		@Schema(description = "目标标题") String goalTitle,
		@Schema(description = "目标描述") String goalDescription,
		@Schema(description = "成长领域枚举名") String domain,
		@Schema(description = "目标截止日期") LocalDate deadline,
		@Schema(description = "给用户看的计划总述") String summary,
		@Schema(description = "每日提醒时刻 HH:mm") String dailyReminderTime,
		@Schema(description = "按天的学习任务列表") List<GrowthPlanProposalDayResponse> days,
		@Schema(description = "确认后关联的目标 ID") Long goalId,
		@Schema(description = "确认后关联的计划版本 ID") Long planId,
		@Schema(description = "草案过期时间") LocalDateTime expiresAt,
		@Schema(description = "创建时间") LocalDateTime createdAt) {}
