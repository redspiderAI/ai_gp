package com.aigp.demo.web.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "推送 token 注册成功")
public record PushTokenRegisteredResponse(
		@Schema(description = "设备 ID") String deviceId,
		@Schema(description = "平台") String platform) {}
