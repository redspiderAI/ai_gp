package com.aigp.demo.web.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

@Schema(description = "标记任务已完成（仅允许从未完成 → 已完成）")
public record CompleteUserTaskRequest(
		@Schema(description = "任务来源：assistant=user_assistant_tasks，growth=tasks", example = "assistant")
				@NotBlank
				@Pattern(regexp = "(?i)assistant|growth", message = "source 须为 assistant 或 growth")
				String source,
		@Schema(description = "任务 ID（须属于当前用户）") @NotNull @Min(1) Long taskId) {}
