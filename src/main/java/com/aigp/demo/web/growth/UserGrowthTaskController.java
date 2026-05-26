package com.aigp.demo.web.growth;

import com.aigp.demo.service.AppUserService;
import com.aigp.demo.service.TaskService;
import com.aigp.demo.service.TaskReminderDueEvaluator;
import com.aigp.demo.service.UserAssistantTaskService;
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
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 成长计划任务（tasks 表）与助手待办（user_assistant_tasks）按日查询。
 * <p>标记完成请用 {@code POST /api/v1/users/me/tasks/complete} 或对话内说明「XX 完成了」。
 */
@RestController
@RequestMapping("/api/v1/users/me/growth-tasks")
@Validated
@RequiredArgsConstructor
@Tag(name = "成长计划任务", description = "按日查询 tasks 与 user_assistant_tasks（完成态见 POST /users/me/tasks/complete）")
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

	@GetMapping("/today")
	@Operation(summary = "查询用户本地「今天」的计划任务")
	public GrowthTaskListResponse listToday(@CurrentUser JwtUserClaims user) {
		return list(user, null);
	}

	private LocalDate resolveQueryDate(Long userId, String date) {
		if (StringUtils.hasText(date)) {
			return parseRequiredDate(date);
		}
		var u = appUserService.requireActive(userId);
		ZoneId zone = TaskReminderDueEvaluator.resolveZone(u.getTimezone());
		return LocalDate.now(zone);
	}

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
