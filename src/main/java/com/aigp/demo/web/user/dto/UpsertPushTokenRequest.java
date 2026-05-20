package com.aigp.demo.web.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "注册或更新 uni-push 2.0 设备标识（push_clientid）")
public record UpsertPushTokenRequest(
		@Schema(description = "平台：ANDROID 或 IOS", example = "ANDROID")
				@NotBlank
				@Size(max = 16)
				String platform,
		@Schema(description = "uni.getPushClientId() 返回值（个推 CID）", example = "xxxxxxxx")
				@NotBlank
				@Size(max = 512)
				String token,
		@Schema(description = "客户端设备 ID，同机重装前应保持稳定", example = "a1b2c3-device-uuid")
				@NotBlank
				@Size(max = 64)
				String deviceId) {}
