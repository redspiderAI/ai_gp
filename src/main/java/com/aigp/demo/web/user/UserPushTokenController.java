package com.aigp.demo.web.user;

import com.aigp.demo.service.UserPushDeviceService;
import com.aigp.demo.web.security.CurrentUser;
import com.aigp.demo.web.security.JwtUserClaims;
import com.aigp.demo.web.user.dto.PushTokenRegisteredResponse;
import com.aigp.demo.web.user.dto.UpsertPushTokenRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * uni-push 2.0 设备 clientId：登录后上报，登出时注销。
 */
@RestController
@RequestMapping("/api/v1/users/me/push-tokens")
@Validated
@RequiredArgsConstructor
@Tag(name = "移动推送", description = "uni-push 2.0 clientId 注册（锁屏/杀进程系统通知）")
@SecurityRequirement(name = "bearerAuth")
public class UserPushTokenController {

	private final UserPushDeviceService userPushDeviceService;

	@PutMapping
	@Operation(summary = "注册或更新本机 uni-push clientId")
	public PushTokenRegisteredResponse upsert(
			@CurrentUser JwtUserClaims user, @Valid @RequestBody UpsertPushTokenRequest body) {
		return userPushDeviceService.upsert(user.userId(), body);
	}

	@DeleteMapping
	@Operation(summary = "注销推送 token（登出）")
	public ResponseEntity<Void> remove(
			@CurrentUser JwtUserClaims user,
			@RequestParam(required = false) String deviceId) {
		userPushDeviceService.remove(user.userId(), deviceId);
		return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
	}
}
