package com.aigp.demo.web.user.dto;

import com.aigp.demo.web.growth.dto.GrowthTaskItemResponse;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "任务标记完成结果")
public record CompleteUserTaskResponse(
		@Schema(description = "assistant 或 growth") String source,
		@Schema(description = "source=assistant 时有值") UserAssistantTaskItemResponse assistantTask,
		@Schema(description = "source=growth 时有值") GrowthTaskItemResponse growthTask) {

	public static CompleteUserTaskResponse fromAssistant(UserAssistantTaskItemResponse task) {
		return new CompleteUserTaskResponse("assistant", task, null);
	}

	public static CompleteUserTaskResponse fromGrowth(GrowthTaskItemResponse task) {
		return new CompleteUserTaskResponse("growth", null, task);
	}
}
