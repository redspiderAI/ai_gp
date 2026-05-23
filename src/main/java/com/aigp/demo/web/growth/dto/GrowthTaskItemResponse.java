package com.aigp.demo.web.growth.dto;

import com.aigp.demo.domain.task.Task;
import com.aigp.demo.service.GrowthTaskTiming;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Schema(description = "成长计划每日任务（tasks 表）")
public record GrowthTaskItemResponse(
		@Schema(description = "任务 ID") Long id,
		@Schema(description = "目标 ID") Long goalId,
		@Schema(description = "计划 ID") Long planId,
		@Schema(description = "标题") String title,
		@Schema(description = "描述") String description,
		@Schema(description = "计划执行日 yyyy-MM-dd") LocalDate scheduledDate,
		@Schema(description = "预估分钟") int estimatedMinutes,
		@Schema(description = "状态") String status,
		@Schema(description = "开始执行时刻") LocalDateTime startedAt,
		@Schema(description = "计划结束时刻（startedAt + estimatedMinutes）") LocalDateTime plannedEndAt,
		@Schema(description = "完成时刻") LocalDateTime completedAt,
		@Schema(description = "实际耗时分钟") Integer actualMinutes) {

	public static GrowthTaskItemResponse fromEntity(Task t) {
		return new GrowthTaskItemResponse(
				t.getId(),
				t.getGoal().getId(),
				t.getPlan().getId(),
				t.getTitle(),
				t.getDescription(),
				t.getScheduledDate(),
				t.getEstimatedMinutes() != null ? t.getEstimatedMinutes() : 0,
				t.getStatus().name(),
				t.getStartedAt(),
				GrowthTaskTiming.plannedEndAt(t),
				t.getCompletedAt(),
				t.getActualMinutes());
	}
}
