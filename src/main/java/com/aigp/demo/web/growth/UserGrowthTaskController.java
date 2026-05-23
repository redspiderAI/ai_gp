package com.aigp.demo.web.growth;

import com.aigp.demo.domain.task.Task;
import com.aigp.demo.service.AppUserService;
import com.aigp.demo.service.TaskService;
import com.aigp.demo.service.TaskReminderDueEvaluator;
import com.aigp.demo.service.UserAssistantTaskService;
import com.aigp.demo.web.growth.dto.GrowthTaskCompleteRequest;
import com.aigp.demo.web.growth.dto.GrowthTaskItemResponse;
import com.aigp.demo.web.growth.dto.GrowthTaskListResponse;
import com.aigp.demo.web.security.CurrentUser;
import com.aigp.demo.web.security.JwtUserClaims;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 成长计划任务（tasks 表）执行与查询；列表接口同时返回当日助手待办（user_assistant_tasks）。
 */
@RestController
@RequestMapping("/api/v1/users/me/growth-tasks")
@Validated
@RequiredArgsConstructor
@Tag(name = "成长计划任务", description = "计划日任务开始执行、提前完成、自动完成与列表")
@SecurityRequirement(name = "bearerAuth")
public class UserGrowthTaskController {

	private final TaskService taskService;
	private final AppUserService appUserService;
	private final UserAssistantTaskService userAssistantTaskService;

	@GetMapping
	@Operation(summary = "按日期查询成长计划任务", description = "date 省略时等同用户本地今天；同时返回 tasks 与 assistantTasks。")
	public GrowthTaskListResponse list(
			@CurrentUser JwtUserClaims user,
			@RequestParam(required = false) String date) {
		LocalDate scheduledDate = resolveQueryDate(user.userId(), date);
		List<GrowthTaskItemResponse> growthItems = taskService.listForUserOnDate(user.userId(), scheduledDate).stream()
				.map(GrowthTaskItemResponse::fromEntity)
				.toList();
		var assistantItems = userAssistantTaskService.listForUserOnDate(user.userId(), scheduledDate);
		return new GrowthTaskListResponse(
				growthItems.size(), growthItems, assistantItems.size(), assistantItems);
	}

	@PostMapping("/{taskId}/start")
	@ResponseStatus(HttpStatus.OK)
	@Operation(summary = "开始执行任务（进行中）")
	public GrowthTaskItemResponse start(@CurrentUser JwtUserClaims user, @PathVariable Long taskId) {
		if (taskId == null || taskId <= 0) {
			throw new IllegalArgumentException("taskId 须大于 0");
		}
		Task task = taskService.startExecution(user.userId(), taskId);
		return GrowthTaskItemResponse.fromEntity(task);
	}

	/**
	 * 提前结束 / 标记完成：进行中可在 plannedEndAt 之前完成；待执行也可在计划日直接完成（不必先 start）。
	 * 已做 JWT 归属校验（{@link TaskService#complete}）。
	 */
	@PostMapping("/{taskId}/complete")
	@ResponseStatus(HttpStatus.OK)
	@Operation(summary = "提前完成（标记已完成）")
	public GrowthTaskItemResponse complete(
			@CurrentUser JwtUserClaims user,
			@PathVariable Long taskId,
			@Valid @RequestBody(required = false) GrowthTaskCompleteRequest request) {
		if (taskId == null || taskId <= 0) {
			throw new IllegalArgumentException("taskId 须大于 0");
		}
		Integer actualMinutes = request != null ? request.actualMinutes() : null;
		Integer qualityScore = request != null ? request.qualityScore() : null;
		Task task = taskService.complete(user.userId(), taskId, actualMinutes, qualityScore);
		return GrowthTaskItemResponse.fromEntity(task);
	}

	@GetMapping("/today")
	@Operation(summary = "查询用户本地「今天」的计划任务")
	public GrowthTaskListResponse listToday(@CurrentUser JwtUserClaims user) {
		return list(user, null);
	}

	/** 解析查询日：有 date 参数则用参数；否则取用户时区下的今天。 */
	private LocalDate resolveQueryDate(Long userId, String date) {
		if (StringUtils.hasText(date)) {
			return parseRequiredDate(date);
		}
		var u = appUserService.requireActive(userId);
		ZoneId zone = TaskReminderDueEvaluator.resolveZone(u.getTimezone());
		return LocalDate.now(zone);
	}

	/** 解析查询参数 date，固定 yyyy-MM-dd（月、日须补零）。 */
	private static LocalDate parseRequiredDate(String raw) {
		if (!StringUtils.hasText(raw)) {
			throw new IllegalArgumentException("date 不能为空");
		}
		try {
			return LocalDate.parse(raw.trim());
		} catch (DateTimeParseException e) {
			throw new IllegalArgumentException("date 格式须为 yyyy-MM-dd（如 2026-05-22）");
		}
	}
}
