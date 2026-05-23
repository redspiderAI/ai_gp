package com.aigp.demo.web.growth.dto;

import com.aigp.demo.web.user.dto.UserAssistantTaskItemResponse;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "当日任务列表：成长计划 tasks + 助手待办 user_assistant_tasks")
public record GrowthTaskListResponse(
		@Schema(description = "成长计划 tasks 条数") int count,
		@Schema(description = "成长计划 tasks 表任务") List<GrowthTaskItemResponse> tasks,
		@Schema(description = "助手待办条数（截止日为查询日）") int assistantCount,
		@Schema(description = "user_assistant_tasks 表任务") List<UserAssistantTaskItemResponse> assistantTasks) {}
