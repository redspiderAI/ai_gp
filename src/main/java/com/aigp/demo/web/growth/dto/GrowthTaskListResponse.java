package com.aigp.demo.web.growth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "成长计划任务列表")
public record GrowthTaskListResponse(
		@Schema(description = "条数") int count, @Schema(description = "任务列表") List<GrowthTaskItemResponse> tasks) {}
