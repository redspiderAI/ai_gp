package com.aigp.demo.web.growth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;

@Schema(description = "计划草案中的单日任务")
public record GrowthPlanProposalDayResponse(
		@Schema(description = "第几天，从 1 起") int dayIndex,
		@Schema(description = "计划执行日 yyyy-MM-dd") LocalDate scheduledDate,
		@Schema(description = "当日任务标题") String title,
		@Schema(description = "当日任务说明") String description,
		@Schema(description = "预计耗时（分钟）") int estimatedMinutes) {}
