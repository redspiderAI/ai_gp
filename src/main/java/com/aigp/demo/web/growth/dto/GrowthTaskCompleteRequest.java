package com.aigp.demo.web.growth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * 提前完成成长计划任务（可选反馈字段）。
 */
@Schema(description = "提前完成成长计划任务")
public record GrowthTaskCompleteRequest(
		@Schema(description = "实际耗时（分钟），可选", example = "25")
				@Min(1)
				@Max(1440)
				Integer actualMinutes,
		@Schema(description = "自评质量 1～5，可选", example = "4")
				@Min(1)
				@Max(5)
				Integer qualityScore) {}
