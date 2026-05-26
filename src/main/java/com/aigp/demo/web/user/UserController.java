package com.aigp.demo.web.user;

import com.aigp.demo.service.AppUserService;
import com.aigp.demo.service.UserAssistantTaskService;
import com.aigp.demo.service.UserAvatarService;
import com.aigp.demo.service.UserTaskCompletionService;
import com.aigp.demo.web.user.dto.CompleteUserTaskRequest;
import com.aigp.demo.web.user.dto.CompleteUserTaskResponse;
import com.aigp.demo.web.security.CurrentUser;
import com.aigp.demo.web.security.JwtUserClaims;
import com.aigp.demo.web.user.dto.PatchOnboardingRequest;
import com.aigp.demo.web.user.dto.UpdateProfileRequest;
import com.aigp.demo.web.user.dto.UserAssistantTaskListResponse;
import com.aigp.demo.web.user.dto.UserProfileResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 当前登录用户资料：查询、修改与注销（软删除）。
 */
@RestController
@RequestMapping("/api/v1/users")
@Validated
@RequiredArgsConstructor
@Tag(name = "用户资料", description = "当前登录用户画像与注销")
@SecurityRequirement(name = "bearerAuth")
public class UserController {

	private final AppUserService appUserService;
	private final UserAssistantTaskService userAssistantTaskService;
	private final UserTaskCompletionService userTaskCompletionService;
	private final UserAvatarService userAvatarService;

	/**
	 * [当前用户] 返回基本画像字段（需有效访问令牌且账号状态为正常）。
	 */
	@GetMapping("/me")
	@Operation(summary = "获取当前用户资料")
	public UserProfileResponse me(@CurrentUser JwtUserClaims user) {
		var u = appUserService.requireActive(user.userId());
		return appUserService.toProfileResponse(u);
	}

	/**
	 * [更新资料] 部分更新昵称、头像、每周可投入小时数；可选绑定大陆手机号（与登录密码共用，绑定后可手机号+密码登录）。
	 */
	@PatchMapping("/me")
	@Operation(summary = "更新当前用户资料")
	public UserProfileResponse patchMe(@CurrentUser JwtUserClaims user, @Valid @RequestBody UpdateProfileRequest body) {
		var updated = appUserService.updateProfile(
				user.userId(), body.nickname(), body.avatarUrl(), body.weeklyHours(), body.phone());
		return appUserService.toProfileResponse(updated);
	}

	/**
	 * [上传头像] 保存图片并自动更新 {@code avatarUrl} 为公开地址（GET /api/v1/public/avatars/{uid}，无需 Token）。
	 */
	@PostMapping(value = "/me/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	@Operation(summary = "上传头像并自动更新资料")
	public UserProfileResponse uploadAvatar(@CurrentUser JwtUserClaims user, @RequestPart("file") MultipartFile file) {
		var updated = userAvatarService.uploadAndBindAvatar(user.userId(), file);
		return appUserService.toProfileResponse(updated);
	}

	@GetMapping("/me/tasks")
	@Operation(summary = "查询当前用户的助手任务清单")
	public UserAssistantTaskListResponse listMyTasks(
			@CurrentUser JwtUserClaims user,
			@RequestParam(required = false) String status) {
		return userAssistantTaskService.listTasksForUser(user.userId(), status);
	}

	@PostMapping("/me/tasks/complete")
	@Operation(
			summary = "标记任务已完成",
			description = "仅允许从未完成 → 已完成。source=assistant（user_assistant_tasks，OPEN→DONE）或 growth（tasks，PENDING/IN_PROGRESS→COMPLETED）。聊天内说「XX 完成了」走 AI 工具，与本接口等价。")
	public CompleteUserTaskResponse completeMyTask(
			@CurrentUser JwtUserClaims user, @Valid @RequestBody CompleteUserTaskRequest body) {
		return userTaskCompletionService.complete(user.userId(), body.source(), body.taskId());
	}

	@PatchMapping("/me/onboarding")
	@Operation(summary = "更新首次登录用户画像（年龄、职业、爱好、昵称、探索方向等）")
	public UserProfileResponse patchOnboarding(
			@CurrentUser JwtUserClaims user, @Valid @RequestBody PatchOnboardingRequest body) {
		var updated = appUserService.updateOnboardingProfile(
				user.userId(),
				body.age(),
				body.occupation(),
				body.hobbies(),
				body.nickname(),
				body.explorationInterests(),
				body.onboardingCompleted());
		return appUserService.toProfileResponse(updated);
	}

	/**
	 * [注销账号] 将用户状态置为注销并撤销全部会话；后续需重新注册（待注册开放）才能使用产品。
	 */
	@DeleteMapping("/me")
	@Operation(summary = "注销当前账号（软删除）")
	public ResponseEntity<Void> deleteMe(@CurrentUser JwtUserClaims user) {
		appUserService.markDeletedAccount(user.userId());
		return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
	}
}
